using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;
using System.Diagnostics;

namespace TermWin.Windows;

public sealed class MainForm : Form
{
    private readonly WebView2 web = new() { Dock = DockStyle.Fill };
    private readonly Label status = new() { Text = "Pronto", AutoSize = false, TextAlign = ContentAlignment.MiddleLeft, Padding = new Padding(10, 0, 0, 0), Dock = DockStyle.Fill };
    private readonly string assetsDir = Path.Combine(AppContext.BaseDirectory, "Assets");
    private Panel toolbar = new();

    public MainForm()
    {
        Text = "TermWin";
        MinimumSize = new Size(900, 600);
        Size = new Size(1280, 800);
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Color.FromArgb(32, 32, 32);
        BuildChrome();
        Shown += async (_, _) => await InitializeBrowserAsync();
    }

    private void BuildChrome()
    {
        toolbar = new Panel { Dock = DockStyle.Top, Height = 48, BackColor = Color.FromArgb(28, 28, 28), Padding = new Padding(8, 6, 8, 6) };
        var flow = new FlowLayoutPanel { Dock = DockStyle.Fill, WrapContents = false, AutoScroll = true, BackColor = Color.Transparent };
        AddButton(flow, "▦  Início", () => NavigateAsset("template_windows10.html"));
        AddButton(flow, "✦  IA", () => NavigateAsset("template_ai.html"));
        AddButton(flow, "▶  Vídeos", () => NavigateAsset("template_youtube.html"));
        AddButton(flow, "↻  Recarregar", () => { if (web.CoreWebView2 != null) web.Reload(); });
        AddButton(flow, "↗  Abrir site", OpenSiteDialog);
        toolbar.Controls.Add(flow);

        var bottom = new Panel { Dock = DockStyle.Bottom, Height = 26, BackColor = Color.FromArgb(24, 24, 24) };
        bottom.Controls.Add(status);
        Controls.Add(web);
        Controls.Add(bottom);
        Controls.Add(toolbar);
    }

    private static void AddButton(FlowLayoutPanel host, string label, Action action)
    {
        var b = new Button { Text = label, AutoSize = true, Height = 34, FlatStyle = FlatStyle.Flat, ForeColor = Color.White, BackColor = Color.FromArgb(48, 48, 48), Margin = new Padding(3, 0, 5, 0), Padding = new Padding(8, 0, 8, 0), Cursor = Cursors.Hand };
        b.FlatAppearance.BorderColor = Color.FromArgb(70, 70, 70);
        b.Click += (_, _) => action();
        host.Controls.Add(b);
    }

    private async Task InitializeBrowserAsync()
    {
        try
        {
            Directory.CreateDirectory(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "TermWin"));
            var userData = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "TermWin", "WebView2");
            var env = await CoreWebView2Environment.CreateAsync(null, userData);
            await web.EnsureCoreWebView2Async(env);
            web.CoreWebView2.Settings.AreDefaultContextMenusEnabled = true;
            web.CoreWebView2.Settings.AreDevToolsEnabled = true;
            web.CoreWebView2.Settings.IsStatusBarEnabled = false;
            web.CoreWebView2.NavigationStarting += (_, _) => SetStatus("Carregando…");
            web.CoreWebView2.NavigationCompleted += (_, e) => SetStatus(e.IsSuccess ? "TermWin pronto" : "Não foi possível carregar esta página");
            web.CoreWebView2.NewWindowRequested += (_, e) =>
            {
                e.Handled = true;
                if (Uri.TryCreate(e.Uri, UriKind.Absolute, out var uri) && (uri.Scheme == "https" || uri.Scheme == "http"))
                    Process.Start(new ProcessStartInfo(uri.AbsoluteUri) { UseShellExecute = true });
            };
            NavigateAsset("template_windows10.html");
        }
        catch (Exception ex)
        {
            MessageBox.Show(this,
                "Não foi possível iniciar o mecanismo WebView2. Instale o Microsoft Edge WebView2 Runtime e abra o TermWin novamente.\n\nDetalhes: " + ex.Message,
                "TermWin — componente ausente", MessageBoxButtons.OK, MessageBoxIcon.Error);
            SetStatus("WebView2 não disponível");
        }
    }

    private void NavigateAsset(string file)
    {
        var path = Path.Combine(assetsDir, file);
        if (!File.Exists(path))
        {
            MessageBox.Show(this, "Arquivo do projeto não encontrado: " + path, "TermWin", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return;
        }
        if (web.CoreWebView2 != null) web.CoreWebView2.Navigate(new Uri(path).AbsoluteUri);
        else if (web.IsHandleCreated) SetStatus("Inicializando navegador interno…");
    }

    private void OpenSiteDialog()
    {
        var value = Microsoft.VisualBasic.Interaction.InputBox("Digite o endereço do site:", "Abrir site", "https://");
        if (string.IsNullOrWhiteSpace(value)) return;
        if (!value.StartsWith("http://", StringComparison.OrdinalIgnoreCase) && !value.StartsWith("https://", StringComparison.OrdinalIgnoreCase)) value = "https://" + value;
        if (Uri.TryCreate(value, UriKind.Absolute, out var uri) && (uri.Scheme == "http" || uri.Scheme == "https") && web.CoreWebView2 != null)
            web.CoreWebView2.Navigate(uri.AbsoluteUri);
        else
            MessageBox.Show(this, "Endereço inválido ou navegador ainda não inicializado.", "TermWin");
    }

    private void SetStatus(string text) => status.Text = "  " + text;
}
