using System.Drawing;
using System.Threading;
using Forms = System.Windows.Forms;

namespace Selene.Windows;

public partial class App : System.Windows.Application
{
    private Mutex? singleInstanceMutex;
    private Forms.NotifyIcon? trayIcon;
    private MainWindow? mainWindow;
    private bool exiting;

    private void OnStartup(object sender, System.Windows.StartupEventArgs e)
    {
        singleInstanceMutex = new Mutex(true, @"Local\SELENE.Windows", out var createdNew);
        if (!createdNew)
        {
            System.Windows.MessageBox.Show("SELENE 已在运行。", "SELENE", System.Windows.MessageBoxButton.OK, System.Windows.MessageBoxImage.Information);
            Shutdown();
            return;
        }

        mainWindow = new MainWindow(e.Args.Contains("--minimized", StringComparer.OrdinalIgnoreCase));
        mainWindow.Closing += (_, args) =>
        {
            if (exiting) return;
            args.Cancel = true;
            mainWindow.Hide();
        };
        CreateTrayIcon();
        mainWindow.Show();
    }

    private void CreateTrayIcon()
    {
        var menu = new Forms.ContextMenuStrip();
        menu.Items.Add("显示 SELENE", null, (_, _) => ShowMainWindow());
        menu.Items.Add("立即采集", null, async (_, _) =>
        {
            if (mainWindow is not null) await mainWindow.CaptureNowAsync();
        });
        menu.Items.Add(new Forms.ToolStripSeparator());
        menu.Items.Add("退出", null, (_, _) => ExitApplication());

        trayIcon = new Forms.NotifyIcon
        {
            Icon = SystemIcons.Application,
            Text = "SELENE",
            Visible = true,
            ContextMenuStrip = menu,
        };
        trayIcon.DoubleClick += (_, _) => ShowMainWindow();
    }

    private void ShowMainWindow()
    {
        if (mainWindow is null) return;
        mainWindow.Show();
        mainWindow.WindowState = System.Windows.WindowState.Normal;
        mainWindow.Activate();
    }

    public void ExitApplication()
    {
        exiting = true;
        trayIcon?.Dispose();
        mainWindow?.AllowClose();
        Shutdown();
    }

    private void OnExit(object sender, System.Windows.ExitEventArgs e)
    {
        trayIcon?.Dispose();
        singleInstanceMutex?.Dispose();
    }
}
