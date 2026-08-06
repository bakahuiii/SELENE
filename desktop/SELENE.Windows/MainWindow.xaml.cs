using System.ComponentModel;
using System.Windows;
using System.Windows.Threading;
using Microsoft.Win32;
using Selene.Windows.Core;

namespace Selene.Windows;

public partial class MainWindow : Window
{
    private readonly SettingsStore settingsStore = new();
    private readonly DesktopCollector collector = new();
    private readonly DispatcherTimer foregroundTimer = new() { Interval = TimeSpan.FromSeconds(10) };
    private readonly DispatcherTimer captureTimer = new();
    private DesktopSettings settings = DesktopSettings.Default;
    private bool loading;
    private bool closingAllowed;
    private bool captureInProgress;

    public MainWindow(bool startMinimized)
    {
        InitializeComponent();
        foregroundTimer.Tick += (_, _) => collector.ObserveForeground();
        captureTimer.Tick += async (_, _) => await CaptureNowAsync();
        Loaded += async (_, _) => await InitializeAsync(startMinimized);
    }

    private async Task InitializeAsync(bool startMinimized)
    {
        loading = true;
        settings = settingsStore.Load().Normalize();
        OutputDirectoryBox.Text = settings.ExportDirectory ?? string.Empty;
        AutomaticCollectionBox.IsChecked = settings.AutomaticCollection;
        StartupBox.IsChecked = settings.StartWithWindows && WindowsStartup.CanConfigure;
        StartupBox.IsEnabled = WindowsStartup.CanConfigure;
        SelectInterval(settings.CaptureIntervalMinutes);
        loading = false;

        foregroundTimer.Start();
        collector.ObserveForeground();
        ConfigureAutomaticCollection();
        UpdateStatus();
        if (settings.AutomaticCollection && HasOutputDirectory()) await CaptureNowAsync();
        if (startMinimized && settings.AutomaticCollection && HasOutputDirectory()) Hide();
    }

    private void SelectInterval(int minutes)
    {
        foreach (var item in IntervalBox.Items.OfType<System.Windows.Controls.ComboBoxItem>())
        {
            if (item.Tag?.ToString() == minutes.ToString())
            {
                IntervalBox.SelectedItem = item;
                return;
            }
        }
        IntervalBox.SelectedIndex = 1;
    }

    private void ChooseDirectory_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFolderDialog { Title = "选择 SELENE 导出文件夹" };
        if (dialog.ShowDialog(this) != true) return;
        OutputDirectoryBox.Text = dialog.FolderName;
        PersistSettings();
    }

    private async void CaptureNow_Click(object sender, RoutedEventArgs e) => await CaptureNowAsync();

    public async Task CaptureNowAsync()
    {
        if (captureInProgress) return;
        if (!HasOutputDirectory())
        {
            StatusText.Text = "请先选择 SELENE 导出文件夹。";
            return;
        }

        captureInProgress = true;
        CaptureButton.IsEnabled = false;
        StatusText.Text = "正在写入新的 SELENE 不可变快照...";
        try
        {
            var result = await collector.CaptureAsync(OutputDirectoryBox.Text);
            StatusText.Text = $"已写入 {result.EventCount} 条事件（{FormatBytes(result.ByteCount)}）：{result.SnapshotDirectory}";
        }
        catch (Exception exception)
        {
            AppLogger.Error("snapshot_write_failed", exception);
            StatusText.Text = $"采集失败：{exception.Message}";
        }
        finally
        {
            captureInProgress = false;
            CaptureButton.IsEnabled = true;
        }
    }

    private void SettingsChanged(object sender, RoutedEventArgs e)
    {
        if (!loading) PersistSettings();
    }

    private void SettingsChanged(object sender, System.Windows.Controls.SelectionChangedEventArgs e)
    {
        if (!loading) PersistSettings();
    }

    private void PersistSettings()
    {
        var interval = (IntervalBox.SelectedItem as System.Windows.Controls.ComboBoxItem)?.Tag?.ToString();
        settings = new DesktopSettings(
            string.IsNullOrWhiteSpace(OutputDirectoryBox.Text) ? null : OutputDirectoryBox.Text.Trim(),
            int.TryParse(interval, out var minutes) ? minutes : 15,
            AutomaticCollectionBox.IsChecked == true,
            StartupBox.IsChecked == true && WindowsStartup.CanConfigure
        ).Normalize();
        settingsStore.Save(settings);
        WindowsStartup.Apply(settings.StartWithWindows);
        ConfigureAutomaticCollection();
        UpdateStatus();
    }

    private void ConfigureAutomaticCollection()
    {
        captureTimer.Stop();
        if (settings.AutomaticCollection && HasOutputDirectory())
        {
            captureTimer.Interval = TimeSpan.FromMinutes(settings.CaptureIntervalMinutes);
            captureTimer.Start();
        }
    }

    private bool HasOutputDirectory() => !string.IsNullOrWhiteSpace(OutputDirectoryBox.Text)
        && Directory.Exists(OutputDirectoryBox.Text);

    private static string FormatBytes(long value) => value < 1024
        ? $"{value} B"
        : value < 1024 * 1024
            ? $"{value / 1024d:0.0} KB"
            : $"{value / (1024d * 1024d):0.0} MB";

    private void UpdateStatus()
    {
        if (!HasOutputDirectory())
        {
            StatusText.Text = "未选择导出目录。选择后可立即采集或启用自动采集。";
            return;
        }
        var auto = settings.AutomaticCollection
            ? $"自动采集已启用，每 {settings.CaptureIntervalMinutes} 分钟一次。"
            : "自动采集已暂停。";
        StatusText.Text = $"{auto} 前台活动采样每 10 秒进行一次。";
    }

    private void Exit_Click(object sender, RoutedEventArgs e) => ((App)System.Windows.Application.Current).ExitApplication();

    public void AllowClose()
    {
        closingAllowed = true;
        foregroundTimer.Stop();
        captureTimer.Stop();
        Close();
    }

    protected override void OnClosing(CancelEventArgs e)
    {
        if (!closingAllowed)
        {
            e.Cancel = true;
            Hide();
            return;
        }
        base.OnClosing(e);
    }
}
