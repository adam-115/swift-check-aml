@echo off
setlocal enabledelayedexpansion
rem ------------------------------------------------------------------
rem Copie les JAR des plugins (swiftcheck-aml-plugins\**\target\*.jar)
rem vers le dossier de destination des plugins PF4J.
rem
rem Usage :
rem   copy-plugins.bat "D:\test parser 2\plugins"   (destination explicite)
rem   copy-plugins.bat                              (utilise le dossier par defaut)
rem ------------------------------------------------------------------

set "ROOT=%~dp0"
set "DEFAULT_DEST=D:\test parser 2\plugins"

set "DEST=%~1"
if "%DEST%"=="" set "DEST=%PLUGINS_DEST%"

if "%DEST%"=="" (
    echo Dossier par defaut : "%DEFAULT_DEST%"
    set /p "DEST=Dossier destination [Entree pour valider le defaut] : "
)

if "%DEST%"=="" set "DEST=%DEFAULT_DEST%"
set "DEST=%DEST:"=%"

if not exist "%DEST%" (
    echo Creation du dossier "%DEST%"...
    mkdir "%DEST%" || (
        echo [ERREUR] Impossible de creer "%DEST%"
        if "%~1"=="" pause
        exit /b 1
    )
)

echo.
echo Recherche et copie des JARs vers "%DEST%"...
echo.

set /a COUNT=0
for /r "%ROOT%swiftcheck-aml-plugins" %%F in (*-SNAPSHOT.jar) do (
    set "P=%%~dpF"
    if not "!P:\target\=!"=="!P!" (
        set "N=%%~nxF"
        if /i not "!N:~0,9!"=="original-" (
            copy /y "%%F" "%DEST%\" >nul && set /a COUNT+=1
        )
    )
)

echo.
echo ------------------------------------------------------------------
echo Termine : !COUNT! fichier(s) copie(s) vers "%DEST%"
echo ------------------------------------------------------------------
echo.

if "%~1"=="" pause
endlocal
