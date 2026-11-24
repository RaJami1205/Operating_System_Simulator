package io.github.rajami1205.filesystem.presentation;

public final class FileSystemApplication {
    private FileSystemApplication() {}

    public static void main(String[] args) {
        if (args.length > 1 || (args.length == 1 && args[0].isBlank())) {
            System.err.println("Uso: java myFileSystem [disco.fs]");
            System.exit(2);
        }
        System.out.println("myFS [unmounted]");
        if (args.length == 1) {
            System.out.println("Ruta recibida: " + args[0].replaceAll("\\p{Cc}", "?"));
        }
    }
}
