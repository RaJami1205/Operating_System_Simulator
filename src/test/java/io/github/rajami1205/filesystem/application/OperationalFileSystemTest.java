package io.github.rajami1205.filesystem.application;

import io.github.rajami1205.filesystem.infrastructure.disk.VirtualDisk;
import io.github.rajami1205.filesystem.infrastructure.disk.IdentityCodec;
import io.github.rajami1205.filesystem.infrastructure.disk.DirectoryCodec;
import io.github.rajami1205.filesystem.model.identity.Identity.User;
import io.github.rajami1205.filesystem.model.disk.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.rajami1205.filesystem.application.IndexedStorage.Step.*;

class OperationalFileSystemTest {
    @TempDir Path dir;
    final DiskGeometry g = new DiskGeometry(1 << 20, 512);
    final char[] pass = "sample secret 42!".toCharArray();
    final char[] next = "next account secret".toCharArray();
    Path path() { return dir.resolve("volume.fs"); }

    OperationalFileSystem format() throws IOException {
        return OperationalFileSystem.format(path(), g, "vol", pass, pass);
    }

    @Test void bootstrapAndManagementAcrossReopen() throws Exception {
        OperationalFileSystem.Actor oldRoot;
        int home;
        try (var fs = format()) {
            assertThrows(SecurityException.class, () -> fs.list(null, 0, "/"));
            oldRoot = fs.authenticate("root", pass);
            assertEquals(List.of(new DirectoryEntry(1,"root"), new DirectoryEntry(2,"user")), fs.list(oldRoot,0,"/"));
            assertEquals(2, fs.createGroup(oldRoot, "engineering").gid());
            assertThrows(IllegalArgumentException.class, () -> fs.createGroup(oldRoot, "engineering"));
            assertThrows(IllegalArgumentException.class, () -> fs.createUser(oldRoot, "x", "X", "root", pass, pass));
            assertThrows(IllegalArgumentException.class, () -> fs.createUser(oldRoot, "x", "X", "absent", pass, pass));
            var alice = fs.createUser(oldRoot, "engineering", " É ", "engineering", pass, pass);
            assertEquals(1, alice.uid());
            assertEquals(2, alice.gid());
            home = alice.home();
            assertThrows(IllegalArgumentException.class, () -> fs.createUser(oldRoot, alice.name(), "A", null, pass, pass));
            var actor = fs.authenticate(alice.name(), pass);
            assertThrows(SecurityException.class, () -> fs.createGroup(actor, "bad"));
            assertThrows(SecurityException.class, () -> fs.createUser(actor, "bad", "Bad", null, pass, pass));
            assertThrows(SecurityException.class, () -> fs.changePassword(actor, "root", pass, next, next));
            assertThrows(SecurityException.class, () -> fs.changePassword(actor, alice.name(), next, next, next));
            assertThrows(SecurityException.class, () -> fs.changePassword(oldRoot, "root", next, next, next));
            fs.changePassword(actor, alice.name(), pass, next, next);
            var wrong = assertThrows(SecurityException.class, () -> fs.authenticate(alice.name(), pass));
            assertEquals(wrong.getMessage(), assertThrows(SecurityException.class, () -> fs.authenticate("missing", pass)).getMessage());
            fs.authenticate(alice.name(), next);
            fs.changePassword(oldRoot, alice.name(), null, pass, pass);
            fs.authenticate(alice.name(), pass);
            fs.create(oldRoot, 0, "/user/conflict", Fcb.Type.DIRECTORY);
            assertThrows(IllegalArgumentException.class, () -> fs.createUser(oldRoot, "conflict", "X", null, pass, pass));
            assertThrows(SecurityException.class, () -> fs.authenticate("conflict", pass));
            assertEquals(2, fs.createUser(oldRoot, "bob", " É ", null, pass, pass).uid());
            var file = fs.create(actor, home, "hello", Fcb.Type.FILE);
            assertEquals(060, file.mode());
            fs.write(actor, home, "hello", new byte[]{1,2,3});
            assertArrayEquals(new byte[]{2,3}, fs.read(actor, home, "hello", 1, 2));
            assertThrows(SecurityException.class, () -> fs.list(fs.authenticate("bob", pass), 0, "/user/engineering"));
            assertThrows(IOException.class, () -> OperationalFileSystem.mount(path()));
        }
        assertEquals(g.diskBytes(), Files.size(path()));
        assertFalse(new String(Files.readAllBytes(path()), StandardCharsets.ISO_8859_1).contains("sample secret 42!"));
        try (var disk = VirtualDisk.open(path())) {
            assertTrue(disk.formatted());
            var u = IdentityCodec.user(g, disk.readBlock(g.userStart()), 1);
            assertEquals(" É ", u.fullName());
            assertEquals(home, u.home());
        }
        try (var fs = OperationalFileSystem.mount(path())) {
            assertThrows(SecurityException.class, () -> fs.list(oldRoot, 0, "/"));
            assertArrayEquals(new byte[]{1,2,3}, fs.read(fs.authenticate("engineering",pass),home,"hello",0,3));
        }
    }

    @Test void invalidFormatAndExistingPaths() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> OperationalFileSystem.format(path(),g,"vol",pass,next));
        assertThrows(IllegalArgumentException.class, () -> OperationalFileSystem.format(path(),g,"é".repeat(33),pass,pass));
        assertFalse(Files.exists(path()));
        try (var disk = VirtualDisk.create(path(),g,"incomplete")) { assertFalse(disk.formatted()); }
        byte[] before = Files.readAllBytes(path());
        assertThrows(IllegalArgumentException.class, () -> OperationalFileSystem.mount(path()));
        assertThrows(FileAlreadyExistsException.class, this::format);
        assertArrayEquals(before, Files.readAllBytes(path()));
    }

    @ParameterizedTest
    @ValueSource(strings={"algorithm","iterations","duplicateUser","duplicateGroup","missingGroup","badOwner",
            "badGroup","homeSlot","sharedHome","homeOwner","homeLocation","systemGroup","rootGroupName"})
    void corruptOperationalImageRemainsUntouched(String defect) throws Exception {
        int ordinaryFile = -1;
        try (var fs = format()) {
            var root = fs.authenticate("root", pass);
            fs.createUser(root, "test", "Test", null, pass, pass);
            fs.createGroup(root, "team");
            if (defect.equals("badOwner")) ordinaryFile = fs.create(root, 0, "/ordinary", Fcb.Type.FILE).id();
        }
        if (defect.equals("homeLocation")) {
            try (var store = IndexedStorage.open(path())) {
                store.replace(2, DirectoryCodec.encode(
                        List.of(new DirectoryEntry(3, "elsewhere")), g));
            }
        } else {
            try (var disk = VirtualDisk.open(path())) {
                int id = switch (defect) {
                    case "algorithm","iterations","duplicateUser","missingGroup","homeSlot","sharedHome" -> g.userStart();
                    case "duplicateGroup","rootGroupName" -> g.groupStart();
                    case "badOwner" -> g.fcbStart() + ordinaryFile * DiskGeometry.FCB_BYTES / g.blockSize();
                    default -> g.fcbStart();
                };
                byte[] page = disk.readBlock(id);
                var b = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN);
                switch (defect) {
                    case "algorithm" -> b.put(257, (byte) 2);
                    case "iterations" -> b.putInt(256 + 16, Integer.MAX_VALUE);
                    case "duplicateUser" -> b.put(256 + 24, "root".getBytes(StandardCharsets.US_ASCII));
                    case "duplicateGroup" -> b.put(256 + 12, "root".getBytes(StandardCharsets.US_ASCII));
                    case "rootGroupName" -> b.put(12, "boot".getBytes(StandardCharsets.US_ASCII));
                    case "missingGroup" -> b.putInt(256 + 8, 63);
                    case "homeSlot" -> b.putInt(256 + 12, 99);
                    case "sharedHome" -> b.putInt(256 + 12, 1);
                    case "badOwner" -> b.putInt(ordinaryFile * DiskGeometry.FCB_BYTES % g.blockSize() + 8, 127);
                    case "badGroup" -> b.putInt(384 + 12, 63);
                    case "homeOwner" -> b.putInt(384 + 8, 0);
                    case "systemGroup" -> b.putInt(128 + 12, 1);
                    default -> throw new AssertionError(defect);
                }
                disk.writeBlock(id, page);
            }
        }
        byte[] before = Files.readAllBytes(path());
        try (var storage = IndexedStorage.open(path())) { storage.inspectHierarchy(); }
        var rejection = assertThrows(IllegalArgumentException.class, () -> OperationalFileSystem.mount(path()));
        if (defect.equals("badOwner")) assertEquals("Inactive FCB owner or group", rejection.getMessage());
        // Each fixture changes only one of the name, HOME and membership inputs to this earlier check.
        if (defect.equals("duplicateUser") || defect.equals("sharedHome"))
            assertEquals("Invalid user membership, name or HOME", rejection.getMessage());
        assertArrayEquals(before, Files.readAllBytes(path()));
    }

    @Test void tableCapacityHasNoPartialAccountOrGroup() throws Exception {
        try (var fs = format()) {
            var root = fs.authenticate("root",pass);
            for (int gid = 2; gid < 64; gid++) assertEquals(gid, fs.createGroup(root,"g" + gid).gid());
        }
        // Fill ordinary accounts with independent homes using the same approved on-disk
        // credential fixture, avoiding 127 redundant 600000-iteration derivations.
        try (var storage = IndexedStorage.open(path())) {
            for (int uid = 1; uid < 128; uid++)
                assertEquals(uid + 2, storage.linkChild(2,"u" + uid,Fcb.Type.DIRECTORY,070,uid,1,new byte[0]).id());
        }
        try (var disk = VirtualDisk.open(path());
             var credential = IdentityCodec.credential(g,disk.readBlock(g.userStart()),0)) {
            for (int uid = 1; uid < 128; uid++) {
                int pageId = IdentityCodec.Table.USERS.block(g,uid);
                byte[] page = disk.readBlock(pageId);
                IdentityCodec.writeUser(g,page,new User(
                        uid,1,uid+2,"u" + uid,"User",0),credential);
                disk.writeBlock(pageId,page);
            }
        }
        byte[] before = Files.readAllBytes(path());
        try (var fs = OperationalFileSystem.mount(path())) {
            var root = fs.authenticate("root",pass);
            assertThrows(IllegalArgumentException.class, () -> fs.createGroup(root,"more"));
            assertThrows(IllegalArgumentException.class, () -> fs.createUser(root,"extra","Extra",null,pass,pass));
            assertEquals(127,fs.list(root,0,"/user").size());
        }
        assertArrayEquals(before,Files.readAllBytes(path()));
    }

    @Test void operationalWriteHonorsBudgetAndAtomicHomeTimestamps() throws Exception {
        var geometry = new DiskGeometry(32L << 20,4096);
        long created;
        int home;
        try (var fs = OperationalFileSystem.format(path(),geometry,"budget",pass,pass)) {
            var root = fs.authenticate("root",pass);
            var u = fs.createUser(root,"member","Member",null,pass,pass);
            created = u.created();
            home = u.home();
            fs.create(root,0,"/test",Fcb.Type.FILE);
            assertThrows(IllegalArgumentException.class, () -> fs.write(root,0,"/test",new byte[17 << 20]));
            assertArrayEquals(new byte[0],fs.read(root,0,"/test",0,0));
            fs.changePassword(root,"member",null,next,next);
        }
        try (var store = IndexedStorage.open(path())) {
            assertEquals(created,store.record(home).created());
            assertEquals(created,store.record(home).modified());
            assertEquals(created,store.record(2).modified());
        }
        try (var disk = VirtualDisk.open(path())) {
            assertEquals(created, IdentityCodec.user(geometry,disk.readBlock(geometry.userStart()),1).created());
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"bootstrapWrite","bootstrapFlush","bootstrapRestoreFlush","validation","publishWrite","publishFlush","restore","restoreFlush"})
    void formatFailurePhases(String phase) throws Exception {
        var primary = new IOException("Injected format failure");
        var secondary = new IOException("Injected restoration failure");
        boolean[] bootstrapped = {false};
        int[] writes = {0};
        var failure = assertThrows(IOException.class, () -> OperationalFileSystem.format(path(),g,"vol",pass,pass,step -> {
            if (step == BOOTSTRAP_PERSISTED) {
                bootstrapped[0] = true;
                if (phase.equals("validation")) throw primary;
            }
            if (!bootstrapped[0]) {
                if (step == WRITE && ++writes[0] == 3 && phase.equals("bootstrapWrite")) throw primary;
                if (step == FLUSH && (phase.equals("bootstrapFlush") || phase.equals("bootstrapRestoreFlush"))) throw primary;
                if (step == RESTORE_FLUSH && phase.equals("bootstrapRestoreFlush")) throw secondary;
            } else {
                if (step == WRITE && phase.equals("publishWrite")) throw primary;
                if (step == FLUSH && (phase.equals("publishFlush") || phase.startsWith("restore"))) throw primary;
                if (step == RESTORE && phase.equals("restore") || step == RESTORE_FLUSH && phase.equals("restoreFlush"))
                    throw secondary;
            }
        }));
        assertSame(primary, failure);
        assertTrue(Files.exists(path()));
        if (phase.equals("bootstrapRestoreFlush")) {
            assertFalse(bootstrapped[0]);
            var suppressed = primary.getSuppressed();
            assertEquals(2, suppressed.length);
            assertSame(secondary, suppressed[0]);
            assertEquals("Persisted bootstrap content indeterminate; format status remains INCOMPLETE", suppressed[1].getMessage());
            assertEquals("FAULTED", suppressed[1].getCause().getMessage());
            try (var disk = VirtualDisk.open(path())) { assertFalse(disk.formatted()); }
        } else if (phase.startsWith("restore")) {
            assertTrue(List.of(primary.getSuppressed()).contains(secondary));
            assertTrue(Arrays.stream(primary.getSuppressed()).anyMatch(e -> e.getMessage().contains("indeterminate")));
        } else {
            try (var disk = VirtualDisk.open(path())) {
                assertFalse(disk.formatted());
                assertEquals(bootstrapped[0], IdentityCodec.group(g,disk.readBlock(g.groupStart()),0) != null);
            }
            assertThrows(IllegalArgumentException.class, () -> OperationalFileSystem.mount(path()));
        }
        assertThrows(FileAlreadyExistsException.class, this::format);
    }

    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void userAndHomeRollback(boolean fault) throws Exception {
        try (var ignored = format()) { }
        byte[] before = Files.readAllBytes(path());
        var primary = new IOException("commit");
        var secondary = new IOException("rollback");
        try (var fs = OperationalFileSystem.mount(path(), step -> {
            if (step == FLUSH) throw primary;
            if (fault && step == RESTORE_FLUSH) throw secondary;
        })) {
            var root = fs.authenticate("root",pass);
            assertSame(primary, assertThrows(IOException.class, () -> fs.createUser(root,"newuser","Person",null,pass,pass)));
            if (fault) {
                assertSame(secondary, primary.getSuppressed()[0]);
                assertThrows(IllegalStateException.class, () -> fs.list(root,0,"/"));
                assertThrows(IOException.class, () -> OperationalFileSystem.mount(path()));
            } else {
                assertTrue(fs.list(root,0,"/user").isEmpty());
                assertThrows(SecurityException.class, () -> fs.authenticate("newuser",pass));
            }
        }
        assertArrayEquals(before, Files.readAllBytes(path()));
    }

    @Test void staleAuthenticationAndForeignAuthorities() throws Exception {
        try (var ignored = format()) { }
        var paused = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var armed = new AtomicBoolean();
        try (var fs = OperationalFileSystem.mount(path(), step -> {
            if (step == CREDENTIAL_SNAPSHOT && armed.compareAndSet(true,false)) {
                paused.countDown();
                try {
                    if (!resume.await(15, TimeUnit.SECONDS)) throw new IOException("Timed out awaiting replacement");
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            }
        }); var other = OperationalFileSystem.format(dir.resolve("another.fs"),g,"other",pass,pass);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var root = fs.authenticate("root",pass);
            assertThrows(SecurityException.class, () -> other.list(root,0,"/"));
            armed.set(true);
            var pending = executor.submit(() -> fs.authenticate("root",pass));
            try {
                assertTrue(paused.await(10,TimeUnit.SECONDS));
                fs.changePassword(root,"root",pass,next,next);
            } finally { resume.countDown(); }
            var failure = assertThrows(ExecutionException.class, () -> pending.get(10,TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, failure.getCause());
            fs.authenticate("root",next);
            assertEquals(2,fs.list(root,0,"/").size());
        }
    }

    @Test void mutableModesGroupsAndAncestorTraversal() throws Exception {
        int home;
        try (var fs = format()) {
            var root = fs.authenticate("root",pass);
            fs.createGroup(root,"team");
            home = fs.createUser(root,"u","User",null,pass,pass).home();
        }
        try (var disk = VirtualDisk.open(path())) {
            byte[] table = disk.readBlock(g.fcbStart());
            ByteBuffer b = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN);
            b.putShort(2,(short)055); // Mutable root mode still permits listing.
            b.putShort(2 * 128 + 2,(short)070); // revoke /user traversal for ordinary users
            b.putInt(home * 128 + 12,2);
            disk.writeBlock(g.fcbStart(),table);
        }
        byte[] before = Files.readAllBytes(path());
        try (var fs = OperationalFileSystem.mount(path())) {
            var u = fs.authenticate("u",pass);
            for (String p : List.of(".", "..", "./x", "../absent"))
                assertThrows(SecurityException.class, () -> fs.list(u,home,p));
            assertThrows(SecurityException.class, () -> fs.list(u,0,"/user/missing/../x"));
            assertThrows(SecurityException.class, () -> fs.create(u,0,"/blocked",Fcb.Type.FILE));
            assertEquals(2, fs.list(u,home,"/").size());
        }
        assertArrayEquals(before, Files.readAllBytes(path()));
    }

    @Test void authorizedTraversalAndFinalAccessAreSeparate() throws Exception {
        int file;
        try (var fs = format()) {
            var root = fs.authenticate("root",pass);
            fs.createUser(root,"u","User",null,pass,pass);
            file = fs.create(root,0,"/shared",Fcb.Type.FILE).id();
            fs.write(root,0,"/shared",new byte[]{42});
        }
        try (var disk = VirtualDisk.open(path())) {
            int address = file * DiskGeometry.FCB_BYTES;
            int block = g.fcbStart() + address / g.blockSize();
            byte[] page = disk.readBlock(block);
            var b = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN);
            int offset = address % g.blockSize();
            b.putInt(offset + 12,1).putShort(offset + 2,(short)064);
            disk.writeBlock(block,page);
        }
        byte[] before = Files.readAllBytes(path());
        var fs = OperationalFileSystem.mount(path());
        OperationalFileSystem.Actor u;
        try (fs) {
            u = fs.authenticate("u",pass);
            assertArrayEquals(new byte[]{42},fs.read(u,0,"/shared",0,1));
            assertThrows(SecurityException.class, () -> fs.write(u,0,"/shared",new byte[]{99}));
            assertThrows(SecurityException.class, () -> fs.create(u,0,"/new",Fcb.Type.FILE));
        }
        assertThrows(IllegalStateException.class, () -> fs.list(u,0,"/"));
        assertArrayEquals(before,Files.readAllBytes(path()));
    }
}
