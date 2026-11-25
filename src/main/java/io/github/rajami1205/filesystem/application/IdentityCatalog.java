package io.github.rajami1205.filesystem.application;

import io.github.rajami1205.filesystem.model.disk.DiskGeometry;
import io.github.rajami1205.filesystem.model.disk.Fcb;
import io.github.rajami1205.filesystem.model.identity.Identity.Group;
import io.github.rajami1205.filesystem.model.identity.Identity.User;
import java.util.BitSet;
import java.util.HashSet;

final class IdentityCatalog {
    final User[] users = new User[DiskGeometry.USER_COUNT];
    final Group[] groups = new Group[DiskGeometry.GROUP_COUNT];

    User user(String name) {
        for (User u : users) if (u != null && u.name().equals(name)) return u;
        return null;
    }

    Group group(String name) {
        for (Group g : groups) if (g != null && g.name().equals(name)) return g;
        return null;
    }

    static int firstFree(Object[] slots, int start) {
        for (int i = start; i < slots.length; i++) if (slots[i] == null) return i;
        throw new IllegalArgumentException("Identity table full");
    }

    void validate(DirectoryHierarchy tree) {
        if (groups[0] == null || !groups[0].name().equals("root") || groups[1] == null || !groups[1].name().equals("users")
                || users[0] == null || !users[0].name().equals("root") || users[0].home() != 1)
            throw new IllegalArgumentException("Invalid reserved identity");
        var groupNames = new HashSet<String>();
        for (Group g : groups)
            if (g != null && !groupNames.add(g.name())) throw new IllegalArgumentException("Duplicate group name");
        systemDirectory(tree, 1, "root", 0);
        systemDirectory(tree, 2, "user", 1);
        var homes = new BitSet();
        var names = new HashSet<String>();
        for (User u : users) {
            if (u == null) continue;
            if (!names.add(u.name()) || homes.get(u.home()) || groups[u.gid()] == null)
                throw new IllegalArgumentException("Invalid user membership, name or HOME");
            homes.set(u.home());
            Fcb home = tree.active(u.home());
            int parent = u.uid() == 0 ? 0 : 2;
            if (home.type() != Fcb.Type.DIRECTORY || home.owner() != u.uid() || home.parent() != parent
                    || tree.lookup(parent, u.name()).child() != home.id())
                throw new IllegalArgumentException("Invalid registered HOME");
        }
        for (Fcb fcb : tree.records)
            if (fcb != null && (users[fcb.owner()] == null || groups[fcb.group()] == null))
                throw new IllegalArgumentException("Inactive FCB owner or group");
    }

    void validateBootstrap(DirectoryHierarchy tree) {
        validate(tree);
        if (!users[0].fullName().equals("root") || tree.active(0).mode() != 075
                || tree.active(1).mode() != 070 || tree.active(2).mode() != 075)
            throw new IllegalArgumentException("Invalid bootstrap defaults");
        for (int i = 1; i < users.length; i++)
            if (users[i] != null) throw new IllegalArgumentException("Unexpected bootstrap user");
        for (int i = 2; i < groups.length; i++)
            if (groups[i] != null) throw new IllegalArgumentException("Unexpected bootstrap group");
        for (int i = 3; i < tree.records.length; i++)
            if (tree.records[i] != null) throw new IllegalArgumentException("Unexpected bootstrap resource");
    }

    private static void systemDirectory(DirectoryHierarchy tree, int id, String name, int group) {
        Fcb fcb = tree.active(id);
        if (tree.lookup(0, name).child() != id || fcb.type() != Fcb.Type.DIRECTORY || fcb.parent() != 0
                || fcb.owner() != 0 || fcb.group() != group)
            throw new IllegalArgumentException("Invalid system directory");
    }
}
