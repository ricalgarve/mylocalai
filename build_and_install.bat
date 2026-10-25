@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

title MyLocal.AI - Compilador e Instalador Android

echo ==============================================================================
echo                         MYLOCAL.AI - SERVIDOR LOCAL
echo           Compilação e Instalação Automatizada para Android
echo ==============================================================================
echo.

:: 1. Verificar Java (JDK 17 recomendado)
echo [*] Verificando instalação do Java...
where java >nul 2>nul
if %errorlevel% neq 0 (
    echo [ERRO] Java não encontrado no PATH do sistema!
    echo        Instale o JDK 17 ou superior e configure o JAVA_HOME.
    echo        Download: https://adoptium.net/ ou via Android Studio.
    echo.
    pause
    exit /b 1
)

for /f "tokens=3" %%g in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    set JAVAVER=%%g
)
echo [OK] Java detectado: !JAVAVER!

:: 2. Verificar Android SDK / local.properties
echo [*] Verificando configuração do Android SDK...
if not exist "local.properties" (
    if defined ANDROID_HOME (
        echo sdk.dir=!ANDROID_HOME:\=/!> local.properties
        echo [OK] local.properties criado com base no ANDROID_HOME.
    ) else if defined ANDROID_SDK_ROOT (
        echo sdk.dir=!ANDROID_SDK_ROOT:\=/!> local.properties
        echo [OK] local.properties criado com base no ANDROID_SDK_ROOT.
    ) else if exist "%LOCALAPPDATA%\Android\Sdk" (
        set SDK_PATH=%LOCALAPPDATA%\Android\Sdk
        echo sdk.dir=!SDK_PATH:\=/!> local.properties
        echo [OK] local.properties criado automaticamente apontando para %LOCALAPPDATA%\Android\Sdk.
    ) else (
        echo [AVISO] Arquivo local.properties não encontrado.
        echo         Se a compilação falhar, defina a variável ANDROID_HOME ou crie
        echo         o arquivo local.properties contendo: sdk.dir=C:/Caminho/Android/Sdk
    )
) else (
    echo [OK] local.properties existente detectado.
)

echo.
:MENU
echo ==============================================================================
echo Escolha uma ação:
echo ==============================================================================
echo [1] Compilar APK (Release otimizado)
echo [2] Compilar APK (Debug)
echo [3] Compilar Release e Instalar direto no celular via ADB
echo [4] Apenas Instalar APK existente no celular via ADB
echo [5] Verificar dispositivos Android conectados (ADB)
echo [6] Executar teste de requisição na API (test_api.bat)
echo [7] Sair
echo ==============================================================================
set /p OPTION="Digite a opção desejada [1-7]: "

if "%OPTION%"=="1" goto BUILD_RELEASE
if "%OPTION%"=="2" goto BUILD_DEBUG
if "%OPTION%"=="3" goto BUILD_AND_INSTALL
if "%OPTION%"=="4" goto INSTALL_ONLY
if "%OPTION%"=="5" goto CHECK_ADB
if "%OPTION%"=="6" goto RUN_TEST_API
if "%OPTION%"=="7" goto EXIT

echo Opção inválida! Tente novamente.
goto MENU

:BUILD_RELEASE
echo.
echo [*] Compilando APK Release (C++ NDK + Kotlin)...
call gradlew.bat assembleRelease
if %errorlevel% neq 0 (
    echo [ERRO] Falha na compilação do APK Release!
    pause
    goto MENU
)
echo.
echo [SUCESSO] APK Release gerado com sucesso!
echo Localização: app\build\outputs\apk\release\app-release.apk
pause
goto MENU

:BUILD_DEBUG
echo.
echo [*] Compilando APK Debug...
call gradlew.bat assembleDebug
if %errorlevel% neq 0 (
    echo [ERRO] Falha na compilação do APK Debug!
    pause
    goto MENU
)
echo.
echo [SUCESSO] APK Debug gerado com sucesso!
echo Localização: app\build\outputs\apk\debug\app-debug.apk
pause
goto MENU

:BUILD_AND_INSTALL
echo.
echo [*] Compilando APK Release...
call gradlew.bat assembleRelease
if %errorlevel% neq 0 (
    echo [ERRO] Falha na compilação.
    pause
    goto MENU
)

:INSTALL_ONLY
echo.
echo [*] Verificando conexão com dispositivo Android via ADB...
where adb >nul 2>nul
if %errorlevel% neq 0 (
    echo [ERRO] ADB não encontrado no PATH!
    echo        Certifique-se de que o Android Platform-Tools está instalado
    echo        ou copie o APK manualmente para o celular.
    pause
    goto MENU
)

adb devices
set APK_PATH=app\build\outputs\apk\release\app-release.apk
if not exist "%APK_PATH%" (
    set APK_PATH=app\build\outputs\apk\debug\app-debug.apk
)
if not exist "%APK_PATH%" (
    if exist "PocketPalServer.apk" (
        set APK_PATH=PocketPalServer.apk
    ) else (
        echo [ERRO] Nenhum arquivo APK encontrado para instalação. Compile primeiro (Opção 1 ou 2).
        pause
        goto MENU
    )
)

echo [*] Instalando %APK_PATH% no celular conectado...
adb install -r "%APK_PATH%"
if %errorlevel% neq 0 (
    echo [ERRO] Falha ao instalar via ADB! Verifique se a Depuração USB está ativa e autorizada.
) else (
    echo [SUCESSO] Aplicativo instalado com sucesso!
    echo [*] Abrindo o app no celular...
    adb shell am start -n com.pocketpal.server/.MainActivity
)
pause
goto MENU

:CHECK_ADB
echo.
echo [*] Dispositivos conectados via ADB:
adb devices
echo.
pause
goto MENU

:RUN_TEST_API
if exist "test_api.bat" (
    call test_api.bat
) else (
    echo test_api.bat não encontrado.
)
goto MENU

:EXIT
echo Finalizado.
exit /b 0
