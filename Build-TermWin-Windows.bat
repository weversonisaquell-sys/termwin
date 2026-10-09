@echo off
setlocal
cd /d "%~dp0windows"
echo ========================================
echo  TermWin - compilacao para Windows x64
echo ========================================
where dotnet >nul 2>nul
if errorlevel 1 (
  echo ERRO: .NET 8 SDK nao encontrado.
  echo Instale o .NET 8 SDK: https://dotnet.microsoft.com/download/dotnet/8.0
  pause
  exit /b 1
)
echo Restaurando dependencias e compilando o TermWin.exe...
dotnet publish TermWin.Windows.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true -o "%~dp0dist"
if errorlevel 1 (
  echo.
  echo FALHA NA COMPILACAO. Confira a mensagem acima e a conexao com a internet.
  pause
  exit /b 1
)
echo.
echo Compilacao concluida.
echo Executavel: %~dp0dist\TermWin.exe
echo IMPORTANTE: copie a pasta Assets junto do TermWin.exe para manter os arquivos HTML e video.
echo O Microsoft Edge WebView2 Runtime tambem precisa estar instalado no Windows.
pause
