using System.ComponentModel;
using System.IO;
using System.Windows;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Microsoft.Win32;
using QRCoder;
using Selene.Windows.Core;

namespace Selene.Windows;

public partial class MainWindow : Window
{
    private const string ApplicationVersion = "0.5.2";
    private readonly SettingsStore settingsStore = new();
    private readonly DesktopCollector collector = new();
    private readonly SyncthingPairingService pairingService = new();
    private readonly DispatcherTimer foregroundTimer = new() { Interval = TimeSpan.FromSeconds(10) };
    private readonly DispatcherTimer captureTimer = new();
    private DesktopSettings settings = DesktopSettings.Default;
    private bool loading;
    private bool closingAllowed;
    private bool captureInProgress;
    private bool foregroundObservationInProgress;
    private SyncthingPairingOffer? currentPairingOffer;

    public MainWindow(bool startMinimized)
    {
        InitializeComponent();
        foregroundTimer.Tick += async (_, _) => await ObserveForegroundAsync();
        captureTimer.Tick += async (_, _) => await CaptureNowAsync();
        Loaded += async (_, _) => await InitializeAsync(startMinimized);
    }

    private async Task InitializeAsync(bool startMinimized)
    {
        loading = true;
        settings = settingsStore.Load().Normalize();
        VersionText.Text = $"Windows {ApplicationVersion}";
        OutputDirectoryBox.Text = settings.ExportDirectory ?? string.Empty;
        SyncInboxDirectoryBox.Text = settings.SyncInboxDirectory ?? DesktopSettings.DefaultSyncInboxDirectory();
        AutomaticCollectionBox.IsChecked = settings.AutomaticCollection;
        StartupBox.IsChecked = settings.StartWithWindows && WindowsStartup.CanConfigure;
        StartupBox.IsEnabled = WindowsStartup.CanConfigure;
        SelectInterval(settings.CaptureIntervalMinutes);
        ApplyCaptureProfile(settings.CaptureProfile ?? DesktopCaptureProfile.Default);
        loading = false;

        foregroundTimer.Start();
        await ObserveForegroundAsync();
        ConfigureAutomaticCollection();
        UpdateStatus();
        await PrepareSyncAsync();
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

    private void ChooseSyncInbox_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFolderDialog { Title = "选择 Android SELENE 同步收件箱" };
        if (dialog.ShowDialog(this) != true) return;
        pairingService.CancelActivePairing();
        SyncInboxDirectoryBox.Text = dialog.FolderName;
        ClearPairingOffer();
        PersistSettings();
        _ = PrepareSyncAsync();
    }

    private async void BeginPairing_Click(object sender, RoutedEventArgs e)
    {
        BeginPairingButton.IsEnabled = false;
        PairingStatusText.Text = "正在准备 Syncthing 和一次性局域网配对端…";
        try
        {
            var inbox = SyncInboxDirectoryBox.Text;
            currentPairingOffer = await Task.Run(() => pairingService.BeginPairingAsync(inbox));
            var qrImage = await Task.Run(() => CreateQrImage(currentPairingOffer.Code));
            PairingCodeBox.Text = currentPairingOffer.Code;
            PairingCodeBox.Visibility = Visibility.Visible;
            PairingQrImage.Source = qrImage;
            PairingQrImage.Visibility = Visibility.Visible;
            CopyPairingCodeButton.IsEnabled = true;
            PairingStatusText.Text = $"二维码将在 {currentPairingOffer.ExpiresAt.ToLocalTime():HH:mm:ss} 过期。请让手机与本机暂时连接同一局域网后扫描。";
            _ = ObservePairingCompletionAsync(currentPairingOffer);
        }
        catch (Exception exception)
        {
            AppLogger.Error("syncthing_pairing_start_failed", exception);
            PairingStatusText.Text = $"无法生成配对码：{exception.Message}";
        }
        finally
        {
            BeginPairingButton.IsEnabled = true;
        }
    }

    private void CopyPairingCode_Click(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrWhiteSpace(PairingCodeBox.Text)) return;
        System.Windows.Clipboard.SetText(PairingCodeBox.Text);
        PairingStatusText.Text = "配对码已复制。它包含 5 分钟有效的一次性令牌，请勿公开发送。";
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
            var result = await collector.CaptureAsync(OutputDirectoryBox.Text, CurrentCaptureProfile());
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
            string.IsNullOrWhiteSpace(SyncInboxDirectoryBox.Text) ? null : SyncInboxDirectoryBox.Text.Trim(),
            int.TryParse(interval, out var minutes) ? minutes : 15,
            AutomaticCollectionBox.IsChecked == true,
            StartupBox.IsChecked == true && WindowsStartup.CanConfigure,
            CurrentCaptureProfile()
        ).Normalize();
        settingsStore.Save(settings);
        WindowsStartup.Apply(settings.StartWithWindows);
        UpdateProfileControls();
        _ = ObserveForegroundAsync();
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
        var profile = settings.CaptureProfile ?? DesktopCaptureProfile.Default;
        var selected = new List<string>();
        if (profile.ForegroundApplications) selected.Add("前台应用");
        if (profile.WindowTitles) selected.Add("窗口标题");
        if (profile.ExecutablePaths) selected.Add("可执行路径");
        if (profile.BrowserUrls) selected.Add("浏览器 URL");
        if (profile.DeviceState) selected.Add("设备状态");
        if (profile.NetworkState) selected.Add("网络状态");
        StatusText.Text = $"{auto} 前台活动采样每 10 秒进行一次。当前写入：{(selected.Count == 0 ? "未选择数据" : string.Join("、", selected))}。";
    }

    private DesktopCaptureProfile CurrentCaptureProfile() => new DesktopCaptureProfile(
        ForegroundApplicationsBox.IsChecked == true,
        WindowTitlesBox.IsChecked == true,
        ExecutablePathsBox.IsChecked == true,
        BrowserUrlsBox.IsChecked == true,
        DeviceStateBox.IsChecked == true,
        NetworkStateBox.IsChecked == true
    ).Normalize();

    private void ApplyCaptureProfile(DesktopCaptureProfile profile)
    {
        ForegroundApplicationsBox.IsChecked = profile.ForegroundApplications;
        WindowTitlesBox.IsChecked = profile.WindowTitles;
        ExecutablePathsBox.IsChecked = profile.ExecutablePaths;
        BrowserUrlsBox.IsChecked = profile.BrowserUrls;
        DeviceStateBox.IsChecked = profile.DeviceState;
        NetworkStateBox.IsChecked = profile.NetworkState;
        UpdateProfileControls();
    }

    private void UpdateProfileControls()
    {
        var foregroundEnabled = ForegroundApplicationsBox.IsChecked == true;
        WindowTitlesBox.IsEnabled = foregroundEnabled;
        ExecutablePathsBox.IsEnabled = foregroundEnabled;
        BrowserUrlsBox.IsEnabled = foregroundEnabled && WindowTitlesBox.IsChecked == true;
    }

    private async Task ObserveForegroundAsync()
    {
        if (foregroundObservationInProgress) return;
        var profile = CurrentCaptureProfile();
        foregroundObservationInProgress = true;
        try
        {
            await Task.Run(() => collector.ObserveForeground(profile));
        }
        catch (Exception exception)
        {
            AppLogger.Error("foreground_observation_failed", exception);
        }
        finally
        {
            foregroundObservationInProgress = false;
        }
    }

    private async Task PrepareSyncAsync()
    {
        if (string.IsNullOrWhiteSpace(SyncInboxDirectoryBox.Text)) return;
        try
        {
            var effectiveInbox = await pairingService.EnsureWindowsSyncAsync(SyncInboxDirectoryBox.Text);
            if (!effectiveInbox.Equals(SyncInboxDirectoryBox.Text, StringComparison.OrdinalIgnoreCase))
            {
                SyncInboxDirectoryBox.Text = effectiveInbox;
                settings = settings with { SyncInboxDirectory = effectiveInbox };
                settingsStore.Save(settings);
            }
            PairingStatusText.Text = "Windows 同步端已就绪。尚未配对时可生成一次性二维码。";
        }
        catch (Exception exception)
        {
            AppLogger.Error("syncthing_prepare_failed", exception);
            PairingStatusText.Text = $"Windows 同步端尚未就绪：{exception.Message}";
        }
    }

    private async Task ObservePairingCompletionAsync(SyncthingPairingOffer offer)
    {
        try
        {
            var result = await offer.Completion;
            await Dispatcher.InvokeAsync(() =>
            {
                if (!ReferenceEquals(currentPairingOffer, offer)) return;
                PairingStatusText.Text = $"Android 已于 {result.PairedAt.ToLocalTime():HH:mm:ss} 配对成功。后续无需再次扫码。";
                PairingQrImage.Visibility = Visibility.Collapsed;
                PairingCodeBox.Visibility = Visibility.Collapsed;
                CopyPairingCodeButton.IsEnabled = false;
            });
        }
        catch (OperationCanceledException)
        {
            // Replacing an offer is expected.
        }
        catch (Exception exception)
        {
            await Dispatcher.InvokeAsync(() =>
            {
                if (ReferenceEquals(currentPairingOffer, offer)) PairingStatusText.Text = exception.Message;
            });
        }
    }

    private static BitmapImage CreateQrImage(string value)
    {
        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode(value, QRCodeGenerator.ECCLevel.M);
        using var qr = new PngByteQRCode(data);
        var image = new BitmapImage();
        using var stream = new MemoryStream(qr.GetGraphic(5));
        image.BeginInit();
        image.CacheOption = BitmapCacheOption.OnLoad;
        image.StreamSource = stream;
        image.EndInit();
        image.Freeze();
        return image;
    }

    private void ClearPairingOffer()
    {
        currentPairingOffer = null;
        PairingQrImage.Source = null;
        PairingQrImage.Visibility = Visibility.Collapsed;
        PairingCodeBox.Text = string.Empty;
        PairingCodeBox.Visibility = Visibility.Collapsed;
        CopyPairingCodeButton.IsEnabled = false;
    }

    private void Exit_Click(object sender, RoutedEventArgs e) => ((App)System.Windows.Application.Current).ExitApplication();

    public void AllowClose()
    {
        closingAllowed = true;
        pairingService.Dispose();
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
