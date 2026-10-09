TermWin — versão desktop nativa para Windows x64

Compilação automática:
1. Envie este projeto para o GitHub.
2. O workflow .github/workflows/build.yml usa um runner Windows e .NET 8 para publicar a versão desktop.
3. No GitHub, abra Actions > Build TermWin EXE > última execução > Artifacts > TermWin-Windows-x64.
4. Baixe e extraia o artefato ZIP.

O projeto desktop está em windows/TermWin.Windows.csproj. O workflow principal foi alterado para gerar o EXE, em vez do APK. O código Android original continua no diretório app/, mas não é compilado por esse workflow.

Requisito de execução: Windows x64. O aplicativo usa WebView2; instale o Microsoft Edge WebView2 Runtime se ele não estiver presente.
