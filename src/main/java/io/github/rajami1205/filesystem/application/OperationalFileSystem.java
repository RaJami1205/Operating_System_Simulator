package io.github.rajami1205.filesystem.application;

import io.github.rajami1205.filesystem.infrastructure.security.Passwords;
import io.github.rajami1205.filesystem.model.disk.*;
import io.github.rajami1205.filesystem.model.identity.Credential;
import io.github.rajami1205.filesystem.model.identity.Identity;
import io.github.rajami1205.filesystem.model.identity.Identity.Group;
import io.github.rajami1205.filesystem.model.identity.Identity.User;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import io.github.rajami1205.filesystem.infrastructure.disk.VirtualDisk;
import static io.github.rajami1205.filesystem.application.DirectoryHierarchy.Mode.*;

/** Operational client boundary. The exclusively owned storage and its actor-free primitives stay private. */
public final class OperationalFileSystem implements AutoCloseable {
    private final IndexedStorage storage;

    private OperationalFileSystem(IndexedStorage storage) { this.storage = storage; }

    public static final class Actor {
        private final OperationalFileSystem authority;
        private final int uid;
        private Actor(OperationalFileSystem authority, int uid) { this.authority = authority; this.uid = uid; }
        public int uid() { return uid; }
    }

    public static OperationalFileSystem format(Path path, DiskGeometry geometry, String volume,
                                              char[] password, char[] confirmation) throws IOException {
        return format(path, geometry, volume, password, confirmation, step -> {});
    }

    static OperationalFileSystem format(Path path, DiskGeometry geometry, String volume, char[] password,
                                        char[] confirmation, IndexedStorage.Completion completion) throws IOException {
        Objects.requireNonNull(path);
        Objects.requireNonNull(geometry);
        Objects.requireNonNull(completion);
        DirectoryEntry.utf8Length(volume, 64);
        try (Credential credential = Passwords.establish(password, confirmation)) {
            var storage = IndexedStorage.createIncomplete(path, geometry, volume, completion);
            try {
                synchronized (storage) {
                    long time = storage.now();
                    try (var tx = storage.begin()) {
                        tx.retainCredentials(1);
                        tx.group(new Group(0, "root", time));
                        tx.group(new Group(1, "users", time));
                        storage.prepareRoot(tx, time);
                        var candidate = tx.hierarchy();
                        Fcb rootHome = storage.prepareChild(tx, candidate, 0, "root", Fcb.Type.DIRECTORY,
                                070, 0, 0, new byte[0], time);
                        storage.prepareChild(tx, candidate, 0, "user", Fcb.Type.DIRECTORY, 075, 0, 1, new byte[0], time);
                        tx.user(new User(0, 0, rootHome.id(), "root", "root", time), credential);
                        tx.validatePhysical();
                        tx.identities().validateBootstrap(tx.hierarchy());
                        try {
                            tx.commit();
                        } catch (IOException | RuntimeException failure) {
                            // Under this exclusion the owner is still open; only failed rollback can fault it.
                            try {
                                storage.ready();
                            } catch (IllegalStateException faulted) {
                                failure.addSuppressed(new IndexedStorage.IndeterminateFormat(
                                        "Persisted bootstrap content indeterminate; format status remains INCOMPLETE", faulted));
                            }
                            throw failure;
                        }
                    }
                    storage.completed(IndexedStorage.Step.BOOTSTRAP_PERSISTED);
                    if (storage.formatted()) throw new IllegalStateException("Bootstrap prematurely published");
                    storage.inspect();
                    try (var persisted = storage.begin()) {
                        persisted.identities().validateBootstrap(persisted.hierarchy());
                    }
                    storage.publishFormat();
                }
                return new OperationalFileSystem(storage);
            } catch (IOException | RuntimeException | Error e) {
                closeOnFailure(storage, e);
                throw e;
            }
        }
    }

    public static OperationalFileSystem mount(Path path) throws IOException {
        return mount(path, step -> {});
    }

    static OperationalFileSystem mount(Path path, IndexedStorage.Completion completion) throws IOException {
        var storage = IndexedStorage.open(path, Clock.systemUTC(), completion);
        try {
            if (!storage.formatted()) throw new IllegalArgumentException("Incomplete filesystem");
            validateIdentities(storage);
            return new OperationalFileSystem(storage);
        } catch (IOException | RuntimeException | Error e) {
            closeOnFailure(storage, e);
            throw e;
        }
    }

    private static void validateIdentities(IndexedStorage storage) throws IOException {
        try (var tx = storage.begin()) { tx.identities().validate(tx.hierarchy()); }
    }

    private static void closeOnFailure(IndexedStorage storage, Throwable failure) {
        try { storage.close(); } catch (IOException e) { failure.addSuppressed(e); }
    }

    private static SecurityException denied() { return new SecurityException("Access denied"); }

    private User actor(Actor actor, IdentityCatalog catalog) {
        if (actor == null || actor.authority != this || catalog.users[actor.uid] == null) throw denied();
        return catalog.users[actor.uid];
    }

    private void root(Actor actor, IdentityCatalog catalog) {
        if (actor(actor, catalog).uid() != 0) throw denied();
    }

    private record Snapshot(User user, Credential credential) implements AutoCloseable {
        @Override public void close() { credential.close(); }
    }

    private Snapshot snapshot(User u, IndexedStorage.Transaction tx) throws IOException {
        // Verify a fixed-size dummy credential for unknown names through the same KDF.
        Credential credential = u == null ? new Credential(new byte[16], new byte[32]) : tx.credential(u.uid());
        return new Snapshot(u, credential);
    }

    private boolean current(Snapshot snapshot, IdentityCatalog ids, IndexedStorage.Transaction tx) throws IOException {
        User u = snapshot.user();
        if (u == null || !u.equals(ids.users[u.uid()])) return false;
        try (Credential now = tx.credential(u.uid())) { return now.same(snapshot.credential()); }
    }

    public Actor authenticate(String username, char[] password) throws IOException {
        return authenticate(username, password, null, (actor, home) -> actor);
    }

    @FunctionalInterface
    interface Publication<T> { T publish(Actor actor, int home) throws IOException; }

    public VirtualDisk.Owner owner() throws IOException {
        synchronized (storage) { storage.ready(); return storage.owner(); }
    }

    public boolean faulted() { return storage.faulted(); }

    <T> T authenticate(String username, char[] password, Lock coordination, Publication<T> publication)
            throws IOException {
        Snapshot captured;
        if (coordination != null) coordination.lock();
        try {
            synchronized (storage) {
                try (var tx = storage.begin()) { captured = snapshot(tx.identities().user(username), tx); }
            }
        } finally { if (coordination != null) coordination.unlock(); }
        try (captured) {
            storage.completed(IndexedStorage.Step.CREDENTIAL_SNAPSHOT);
            boolean verified = Passwords.verify(captured.credential(), password);
            if (coordination != null) coordination.lock();
            try {
                synchronized (storage) {
                    try (var tx = storage.begin()) {
                        if (!verified || !current(captured, tx.identities(), tx)) throw denied();
                        return publication.publish(new Actor(this, captured.user().uid()), captured.user().home());
                    }
                }
            } finally { if (coordination != null) coordination.unlock(); }
        }
    }

    public Group createGroup(Actor actor, String name) throws IOException {
        synchronized (storage) {
            try (var tx = storage.begin()) {
                var ids = tx.identities();
                root(actor, ids);
                Identity.name(name);
                if (ids.group(name) != null) throw new IllegalArgumentException("Duplicate group name");
                var group = new Group(IdentityCatalog.firstFree(ids.groups, 2), name, storage.now());
                tx.group(group);
                tx.commit();
                return group;
            }
        }
    }

    public User createUser(Actor actor, String username, String fullName, String primaryGroup,
                           char[] password, char[] confirmation) throws IOException {
        synchronized (storage) {
            try (var tx = storage.begin()) {
                var ids = tx.identities();
                root(actor, ids);
                Identity.name(username);
                Identity.fullName(fullName);
                checkNewAccount(ids, tx.hierarchy(), username, primaryGroup);
            }
        }
        try (Credential credential = Passwords.establish(password, confirmation)) {
            synchronized (storage) {
                try (var tx = storage.begin()) {
                    tx.retainCredentials(1);
                    var ids = tx.identities();
                    root(actor, ids);
                    var tree = tx.hierarchy();
                    Group group = checkNewAccount(ids, tree, username, primaryGroup);
                    int uid = IdentityCatalog.firstFree(ids.users, 1);
                    long time = storage.now();
                    Fcb home = storage.prepareChild(tx, tree, 2, username, Fcb.Type.DIRECTORY,
                            070, uid, group.gid(), new byte[0], time);
                    var user = new User(uid, group.gid(), home.id(), username, fullName, time);
                    tx.user(user, credential);
                    tx.identities().validate(tree);
                    tx.commit();
                    return user;
                }
            }
        }
    }

    private static Group checkNewAccount(IdentityCatalog ids, DirectoryHierarchy tree, String username, String primaryGroup) {
        Group group = ids.group(primaryGroup == null ? "users" : primaryGroup);
        if (group == null || group.gid() == 0 || ids.user(username) != null)
            throw new IllegalArgumentException("Invalid primary group or duplicate user");
        if (tree.entries(2).stream().anyMatch(entry -> entry.name().equals(username)))
            throw new IllegalArgumentException("HOME name already in use");
        IdentityCatalog.firstFree(ids.users, 1);
        for (int slot = 1; slot < tree.records.length; slot++)
            if (tree.records[slot] == null) return group;
        throw new IllegalArgumentException("FCB table full");
    }

    public void changePassword(Actor actor, String username, char[] oldPassword,
                               char[] password, char[] confirmation) throws IOException {
        Snapshot target;
        boolean own;
        synchronized (storage) {
            try (var tx = storage.begin()) {
                var ids = tx.identities();
                User acting = actor(actor, ids);
                own = acting.name().equals(username);
                if (!own && acting.uid() != 0) throw denied();
                User destination = ids.user(username);
                if (destination == null) throw denied();
                target = snapshot(destination, tx);
            }
        }
        try (target) {
            if (own && !Passwords.verify(target.credential(), oldPassword)) throw denied();
            try (Credential fresh = Passwords.establish(password, confirmation)) {
                synchronized (storage) {
                    try (var tx = storage.begin()) {
                        tx.retainCredentials(2);
                        var ids = tx.identities();
                        User acting = actor(actor, ids);
                        if ((acting.uid() != 0 && acting.uid() != target.user().uid()) || !current(target, ids, tx))
                            throw denied();
                        tx.user(target.user(), fresh);
                        tx.commit();
                    }
                }
            }
        }
    }

    private User activeActor(Actor actor) throws IOException {
        try (var tx = storage.begin()) { return actor(actor, tx.identities()); }
    }

    public List<DirectoryEntry> list(Actor actor, int cwd, String path) throws IOException {
        synchronized (storage) {
            User u = activeActor(actor);
            var tree = storage.hierarchy();
            Fcb dir = tree.resolveAuthorized(cwd, path, FOLLOW_FINAL_LINK, u).resource();
            u.require(dir, 5);
            return List.copyOf(tree.entries(dir.id()));
        }
    }

    public int directory(Actor actor, int cwd, String path) throws IOException {
        synchronized (storage) {
            User user = activeActor(actor);
            Fcb target = storage.hierarchy().resolveAuthorized(cwd, path, FOLLOW_FINAL_LINK, user).resource();
            user.require(target, 1);
            if (target.type() != Fcb.Type.DIRECTORY) throw new DirectoryHierarchy.InvalidType();
            return target.id();
        }
    }

    public int regularFile(Actor actor, int cwd, String path, boolean write) throws IOException {
        synchronized (storage) { return file(actor, cwd, path, write ? 2 : 4).id(); }
    }

    private Fcb file(Actor actor, int cwd, String path, int mask) throws IOException {
        User u = activeActor(actor);
        Fcb fcb = storage.hierarchy().resolveAuthorized(cwd, path, FOLLOW_FINAL_LINK, u).resource();
        u.require(fcb, mask);
        if (fcb.type() != Fcb.Type.FILE) throw new DirectoryHierarchy.InvalidType();
        return fcb;
    }

    public byte[] read(Actor actor, int cwd, String path, long offset, int length) throws IOException {
        synchronized (storage) { return storage.read(file(actor, cwd, path, 4).id(), offset, length); }
    }

    public void write(Actor actor, int cwd, String path, byte[] content) throws IOException {
        synchronized (storage) { storage.replace(file(actor, cwd, path, 2).id(), content); }
    }

    /** Structural empty-child primitive for protected clients. */
    public Fcb create(Actor actor, int cwd, String path, Fcb.Type type) throws IOException {
        synchronized (storage) {
            User u = activeActor(actor);
            if (type != Fcb.Type.FILE && type != Fcb.Type.DIRECTORY) throw new IllegalArgumentException("Invalid child type");
            var tree = storage.hierarchy();
            var parent = tree.resolveAuthorized(cwd, path, RESOLVE_PARENT, u);
            u.require(parent.resource(), 3);
            try (var tx = storage.begin()) {
                Fcb child = storage.prepareChild(tx, tree, parent.resource().id(), parent.finalName(), type,
                        type == Fcb.Type.FILE ? 060 : 070, u.uid(), u.gid(), new byte[0], storage.now());
                tx.commit();
                return child;
            }
        }
    }

    @Override public void close() throws IOException { storage.close(); }
}
