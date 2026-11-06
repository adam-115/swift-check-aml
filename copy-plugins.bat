@echo off
setlocal enabledelayedexpansion
rem ------------------------------------------------------------------
rem Copie les JAR des plugins (swiftcheck-aml-plugins\**\target\*.jar)
rem vers un dossier destination dynamique.
rem
rem Usage :
rem   copy-plugins.bat "D:\test parser 2\plugins"   (destination en argument)
rem   copy-plugins.bat                              (destination demandee)
rem   set PLUGINS_DEST=D:\x & copy-plugins.bat      (variable d'environnement)
rem ------------------------------------------------------------------

set "ROOT=%~dp0"
set "DEST=%~1"
if "%DEST%"=="" set "DEST=%PLUGINS_DEST%"
if "%DEST%"=="" set /p "DEST=Dossier destination : "
if "%DEST%"=="" (
    echo Aucun dossier destination indique.
    exit /b 1
)
set "DEST=%DEST:"=%"

if not exist "%DEST%" (
    mkdir "%DEST%" || (echo Impossible de creer "%DEST%" & exit /b 1)
)

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

echo %COUNT% fichier(s) copie(s) vers "%DEST%"
endlocal
