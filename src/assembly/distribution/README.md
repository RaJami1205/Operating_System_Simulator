# Virtual File System · FS-F01

Con Java 25, extraiga el ZIP y abra PowerShell en la carpeta extraída.

```powershell
. .\setup.ps1
java myFileSystem
java myFileSystem miDiscoDuro.fs
java myFileSystem "discos de clase/mi disco.fs"
```

El setup configura el classpath y añade `--enable-native-access=ALL-UNNAMED` a `JDK_JAVA_OPTIONS` para JLine FFM. Los cambios duran la sesión y se heredan en sus procesos Java. El launcher puede anunciar estas opciones por stderr. Esta versión valida los argumentos y termina en estado `unmounted`.

Desde la carpeta extraída, en CMD use `set "CLASSPATH=.;lib/*"` y `set "JDK_JAVA_OPTIONS=%JDK_JAVA_OPTIONS% --enable-native-access=ALL-UNNAMED"`. En Linux use `export CLASSPATH=".:lib/*"` y `export JDK_JAVA_OPTIONS="${JDK_JAVA_OPTIONS:+$JDK_JAVA_OPTIONS }--enable-native-access=ALL-UNNAMED"`.

## Compatibilidad registrada

En Windows Terminal con PowerShell y Java 25, la prueba manual de JLine 3.30.6 identificó FFM `NativeWinSysTerminal`, tipo `windows-vtp`. Se observaron color ANSI, UTF-8, entrada de contraseña sin contenido visible, lectura raw de `q` (0x71), `Ctrl+X` (0x18), las cuatro flechas y resize de 223×38 a 223×43. Tanto la salida normal como la excepción intencional devolvieron el control a PowerShell. El estado posterior del eco, del cursor y de los colores no se registró por separado.

El IT comprueba que la JVM auxiliar responde tras la salida normal del cliente. El usuario también reportó una prueba satisfactoria tras cerrar físicamente la terminal inicial, usando la misma ruta del archivo de estado.
