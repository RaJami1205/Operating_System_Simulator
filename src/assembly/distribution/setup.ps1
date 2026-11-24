$env:CLASSPATH = "$PSScriptRoot;$PSScriptRoot/lib/*"
$nativeAccess = '--enable-native-access=ALL-UNNAMED'
if (($env:JDK_JAVA_OPTIONS -split '\s+') -notcontains $nativeAccess) {
    $env:JDK_JAVA_OPTIONS = "$env:JDK_JAVA_OPTIONS $nativeAccess".Trim()
}
