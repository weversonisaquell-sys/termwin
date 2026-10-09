TERMWIN PARA WINDOWS NATIVO
===========================

O projeto Android original foi mantido na pasta app/. Foi adicionada uma versao desktop Windows em windows/.

COMO GERAR O EXE
1. Extraia o ZIP inteiro para uma pasta no Windows.
2. Instale o .NET 8 SDK (nao apenas o runtime): https://dotnet.microsoft.com/download/dotnet/8.0
3. Execute Build-TermWin-Windows.bat.
4. O resultado sera windows/../dist/TermWin.exe (pasta TermWin/dist/TermWin.exe).

REQUISITOS PARA EXECUTAR
- Windows 10/11 x64.
- Microsoft Edge WebView2 Runtime: https://developer.microsoft.com/microsoft-edge/webview2/
- Mantenha a pasta Assets ao lado do executavel. O EXE e publicado como arquivo unico para o runtime .NET, mas os modelos HTML e o video ficam em Assets/.

NOTA SOBRE A CONVERSAO
A versao desktop e um aplicativo Windows real (WinForms) que hospeda as telas HTML do projeto e fornece navegacao entre Inicio, IA e Videos. Recursos Android que dependam de APIs exclusivas do telefone, servicos em segundo plano, notificacoes Android ou armazenamento especifico do Android nao sao automaticamente convertidos e precisariam de implementacao Windows separada.
