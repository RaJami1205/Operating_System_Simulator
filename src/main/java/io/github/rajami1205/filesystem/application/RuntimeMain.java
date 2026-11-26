package io.github.rajami1205.filesystem.application;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Internal owner JVM. Only format startup uses stdout, for one private OSFR record. */
public final class RuntimeMain {
    private RuntimeMain() {}
    public static void main(String[] args) {
        if(args.length!=2 || !(args[0].equals("mount") || args[0].equals("format"))) return;
        boolean format=args[0].equals("format");
        boolean reported=false;
        UUID disk=Bootstrap.ZERO, instance=UUID.randomUUID();
        boolean formatted=false;
        try {
            Path path=Path.of(args[1]);
            OperationalFileSystem owner;
            if(format) {
                var reader=new FutureTask<Bootstrap>(()->{ try(var in=System.in) { return Bootstrap.read(in); } });
                Thread.startVirtualThread(reader);
                try(var input=reader.get(10,TimeUnit.SECONDS)) {
                    owner=OperationalFileSystem.format(path,input.geometry(),input.volume(),input.password(),input.confirmation());
                }
                formatted=true;
            } else owner=OperationalFileSystem.mount(path);
            try(owner) {
                disk=owner.owner().uuid();
                try(var server=new RuntimeServer(owner,instance)) {
                    server.publish();
                    if(format) {
                        reported=true;
                        report(new Bootstrap.Result(0,disk,instance));
                    }
                    server.serve();
                }
            }
        } catch(Exception e) {
            if(format && !reported) {
                int code=formatted ? 4 : formatFailure(e);
                try { report(new Bootstrap.Result(code,code==4?disk:Bootstrap.ZERO,code==4?instance:Bootstrap.ZERO)); }
                catch(IOException ignored) {}
            }
        }
    }
    static int formatFailure(Exception failure) {
        if(failure instanceof FileAlreadyExistsException) return 1;
        if(Arrays.stream(failure.getSuppressed()).anyMatch(IndexedStorage.IndeterminateFormat.class::isInstance)) return 3;
        return 2;
    }
    private static void report(Bootstrap.Result result) throws IOException {
        try(var out=new FileOutputStream(FileDescriptor.out)) { result.write(out); }
    }
}
