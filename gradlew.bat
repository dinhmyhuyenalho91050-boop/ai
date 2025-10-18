@ECHO OFF
SETLOCAL

SET DIR=%~dp0

SET APP_BASE_NAME=%~n0
SET APP_HOME=%DIR%

IF EXIST "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" (
    SET WRAPPER_JAR=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar
) ELSE (
    ECHO Gradle wrapper JAR not found: %APP_HOME%\gradle\wrapper\gradle-wrapper.jar
    EXIT /B 1
)

"%JAVA_HOME%\bin\java.exe" -jar "%WRAPPER_JAR%" %*
