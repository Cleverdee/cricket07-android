// The builder window: pick your game folder and an output folder, click Build. It runs android\build.ps1 and shows
// its progress. Compile with builder\compile.ps1 (needs nothing beyond Windows). C# 5, the level Windows' own csc reads.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Security.Cryptography;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Markup;
using System.Windows.Media;
using System.Windows.Threading;
using Microsoft.Win32;
using WinForms = System.Windows.Forms;

static class Program {
    [STAThread]
    static void Main() { new Application().Run(new Builder().Window); }
}

class Stage {
    public string Key, Title;      // Key: how build.ps1's "== " line for this stage begins
    public TextBlock Icon, Label;
    public Stage(string key, string title) { Key = key; Title = title; }
}

class Builder {
    // the version everything was tested on; addresses in host/gamephase.c depend on it
    const string TestedExeSha256 = "d776dba0cc506b968f7d228e036cf42aeb4abe45a8d1ad0d7226cdebf91553a2";
    const string NdkVersion = "27.2.12479018";
    const string SdkTermsUrl = "https://developer.android.com/studio/terms";
    const long NeededFreeBytes = 15L * 1024 * 1024 * 1024;
    const int MaxRepoPathLength = 40;   // longer paths run into the Windows limit in the NDK's object paths
    const int LogLimit = 200000;
    const int CompileCheckSeconds = 5;

    static readonly Brush Good = Hex("#5FD38D"), Warn = Hex("#F2B84B"), Bad = Hex("#F2726F"), Muted = Hex("#8FA69A"), Text = Hex("#EDF4EF");

    public readonly Window Window;
    readonly string mRepo = AppDomain.CurrentDomain.BaseDirectory.TrimEnd('\\');
    readonly TextBox mGame, mOutput, mLog;
    readonly TextBlock mGameStatus, mInfo, mStageTitle, mElapsed, mPercent, mDoneTitle, mDoneText, mFailText;
    readonly CheckBox mRosters, mInstall;
    readonly Grid mSetup, mBuilding, mDone, mFailed;
    readonly ProgressBar mBar;
    readonly StackPanel mStages;
    readonly Border mLogPanel;
    readonly Button mDetails, mCancel;
    readonly DispatcherTimer mTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
    readonly List<Stage> mStageList = new List<Stage>();
    Process mProc;
    DateTime mStarted;
    StreamWriter mLogFile;
    bool mCancelled;
    int mHashGeneration, mCurrentStage;
    string mLastError;

    string SettingsPath { get { return Path.Combine(mRepo, "builder_settings.txt"); } }
    string ApkPath { get { return Path.Combine(mOutput.Text.Trim(), "Cricket07.apk"); } }

    static Brush Hex(string c) { return new SolidColorBrush((Color)ColorConverter.ConvertFromString(c)); }
    T Find<T>(string name) where T : class { return (T)Window.FindName(name); }

    public Builder() {
        Window = (Window)XamlReader.Parse(Ui.Xaml);
        mGame = Find<TextBox>("Game"); mOutput = Find<TextBox>("Output"); mLog = Find<TextBox>("Log");
        mGameStatus = Find<TextBlock>("GameStatus"); mInfo = Find<TextBlock>("Info"); mStageTitle = Find<TextBlock>("StageTitle");
        mElapsed = Find<TextBlock>("Elapsed"); mPercent = Find<TextBlock>("Percent"); mDoneTitle = Find<TextBlock>("DoneTitle");
        mDoneText = Find<TextBlock>("DoneText"); mFailText = Find<TextBlock>("FailText");
        mRosters = Find<CheckBox>("Rosters"); mInstall = Find<CheckBox>("Install");
        mSetup = Find<Grid>("SetupView"); mBuilding = Find<Grid>("BuildView"); mDone = Find<Grid>("DoneView"); mFailed = Find<Grid>("FailView");
        mBar = Find<ProgressBar>("Bar"); mStages = Find<StackPanel>("Stages"); mLogPanel = Find<Border>("LogPanel");
        mDetails = Find<Button>("Details"); mCancel = Find<Button>("Cancel");

        Find<Button>("BrowseGame").Click += delegate { Browse(mGame, "Pick your installed Cricket 07 folder (the one with cricket07.exe)"); };
        Find<Button>("BrowseOutput").Click += delegate { Browse(mOutput, "Pick where to save Cricket07.apk"); };
        Find<Button>("BuildButton").Click += delegate { StartBuild(); };
        mCancel.Click += delegate { CancelBuild(); };
        mDetails.Click += delegate { ShowDetails(mLogPanel.Visibility != Visibility.Visible); };
        Find<Button>("OpenFolder").Click += delegate { Process.Start("explorer.exe", "/select,\"" + ApkPath + "\""); };
        Find<Button>("DoneBack").Click += delegate { Show(mSetup); };
        Find<Button>("FailBack").Click += delegate { Show(mSetup); };
        Find<Button>("LogBack").Click += delegate { Show(mSetup); };
        Find<Button>("FailLog").Click += delegate { Show(mBuilding); ShowDetails(true); SetBuildingButtons(false); };
        Find<Button>("CopyLog").Click += delegate { try { Clipboard.SetText(mLog.Text); } catch (System.Runtime.InteropServices.ExternalException) { } };

        mGame.TextChanged += delegate { CheckGameFolder(); };
        mGame.PreviewDragOver += delegate(object s, DragEventArgs e) { e.Effects = DragDropEffects.Copy; e.Handled = true; };
        mGame.PreviewDrop += delegate(object s, DragEventArgs e) {
            var files = e.Data.GetData(DataFormats.FileDrop) as string[];
            if (files != null && files.Length > 0) mGame.Text = Directory.Exists(files[0]) ? files[0] : Path.GetDirectoryName(files[0]);
            e.Handled = true;
        };
        mTimer.Tick += delegate {
            var e = DateTime.Now - mStarted;
            mElapsed.Text = string.Format("{0}:{1:00}:{2:00}", (int)e.TotalHours, e.Minutes, e.Seconds);
            if ((int)e.TotalSeconds % CompileCheckSeconds == 0) ShowCompileProgress();
        };
        Window.Closing += OnClosing;

        LoadSettings();
        if (mGame.Text.Length == 0) mGame.Text = FindInstalledGame();
        CheckGameFolder();
        Show(mSetup);
    }

    void Show(Grid view) {
        foreach (var v in new[] { mSetup, mBuilding, mDone, mFailed }) v.Visibility = v == view ? Visibility.Visible : Visibility.Collapsed;
    }

    void Browse(TextBox box, string title) {
        using (var d = new WinForms.FolderBrowserDialog { Description = title, SelectedPath = box.Text })
            if (d.ShowDialog() == WinForms.DialogResult.OK) box.Text = d.SelectedPath;
    }

    // ---------------------------------------------------------------- settings and the game folder

    void LoadSettings() {
        mOutput.Text = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
        try {
            foreach (var line in File.ReadAllLines(SettingsPath)) {
                int i = line.IndexOf('=');
                if (i < 0) continue;
                string k = line.Substring(0, i), v = line.Substring(i + 1);
                if (k == "game") mGame.Text = v;
                else if (k == "output") mOutput.Text = v;
                else if (k == "rosters") mRosters.IsChecked = v == "1";
                else if (k == "install") mInstall.IsChecked = v == "1";
            }
        } catch (IOException) { /* first run */ }
    }

    void SaveSettings() {
        try {
            File.WriteAllLines(SettingsPath, new[] {
                "game=" + mGame.Text, "output=" + mOutput.Text,
                "rosters=" + (mRosters.IsChecked == true ? "1" : "0"), "install=" + (mInstall.IsChecked == true ? "1" : "0") });
        } catch (IOException) { /* only a convenience */ }
    }

    static string FindInstalledGame() {
        foreach (var key in new[] { @"SOFTWARE\WOW6432Node\EA SPORTS\Cricket 07", @"SOFTWARE\EA SPORTS\Cricket 07" }) {
            try {
                using (var k = Registry.LocalMachine.OpenSubKey(key)) {
                    var dir = k == null ? null : k.GetValue("Install Dir") as string;
                    if (dir != null && GameFolderOk(dir)) return dir;
                }
            } catch (System.Security.SecurityException) { }
        }
        foreach (var root in new[] { Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles) }) {
            string dir = Path.Combine(root, "EA SPORTS", "Cricket 07");
            if (GameFolderOk(dir)) return dir;
        }
        return "";
    }

    static bool GameFolderOk(string dir) {
        try {
            return dir.Length > 0 && File.Exists(Path.Combine(dir, "cricket07.exe")) && File.Exists(Path.Combine(dir, "Data", "data.gob"));
        } catch (ArgumentException) { return false; }
    }

    void CheckGameFolder() {
        int generation = ++mHashGeneration;
        string dir = mGame.Text.Trim();
        if (dir.Length == 0) { SetGameStatus("Pick or drag in the folder where Cricket 07 is installed.", Muted); return; }
        if (!GameFolderOk(dir)) { SetGameStatus("\u2715  cricket07.exe and Data\\data.gob aren't in this folder.", Bad); return; }
        SetGameStatus("Game found. Checking its version...", Muted);
        ThreadPool.QueueUserWorkItem(delegate {
            string hash = "";
            try {
                using (var sha = SHA256.Create()) using (var f = File.OpenRead(Path.Combine(dir, "cricket07.exe")))
                    hash = BitConverter.ToString(sha.ComputeHash(f)).Replace("-", "").ToLowerInvariant();
            } catch (IOException) { }
            Window.Dispatcher.BeginInvoke((Action)delegate {
                if (generation != mHashGeneration) return;
                if (hash == TestedExeSha256) SetGameStatus("\u2713  Game found: the version this was tested on.", Good);
                else SetGameStatus("!  Game found, but not the version tested. It may build but not play correctly.", Warn);
            });
        });
    }

    void SetGameStatus(string text, Brush color) { mGameStatus.Text = text; mGameStatus.Foreground = color; }

    // ---------------------------------------------------------------- checks before building

    static bool PythonWorks(string exe, string args) {
        try {
            var psi = new ProcessStartInfo(exe, args) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
            using (var p = Process.Start(psi)) {
                string o = p.StandardOutput.ReadToEnd() + p.StandardError.ReadToEnd();
                p.WaitForExit();
                return p.ExitCode == 0 && o.StartsWith("Python 3");
            }
        } catch (System.ComponentModel.Win32Exception) { return false; }
    }

    bool SdkNeedsInstall() {
        string env = Environment.GetEnvironmentVariable("ANDROID_HOME");
        string sdk = !string.IsNullOrEmpty(env) && Directory.Exists(Path.Combine(env, "ndk", NdkVersion))
            ? env : Path.Combine(mRepo, "third_party", "tools", "android-sdk");
        foreach (var p in new[] { "platform-tools", "platforms\\android-35", "build-tools\\35.0.0", "ndk\\" + NdkVersion, "cmake\\3.22.1" })
            if (!Directory.Exists(Path.Combine(sdk, p))) return true;
        return false;
    }

    bool Refuse(string message) {
        MessageBox.Show(Window, message, Window.Title, MessageBoxButton.OK, MessageBoxImage.Warning);
        return false;
    }

    bool ReadyToBuild(out bool acceptLicenses) {
        acceptLicenses = false;
        if (!File.Exists(Path.Combine(mRepo, "android", "build.ps1")))
            return Refuse("This program has to stay in the project folder, next to the android folder.");
        if (mRepo.Contains(" ") || mRepo.Length > MaxRepoPathLength)
            return Refuse("The Android tools can't build from this folder:\n\n" + mRepo +
                          "\n\nIts path has spaces or is too long. Move the whole project folder somewhere short, such as C:\\cricket07, and open this program from there.");
        if (!GameFolderOk(mGame.Text.Trim())) return Refuse("Pick your Cricket 07 folder first: cricket07.exe and Data\\data.gob have to be in it.");
        if (mOutput.Text.Trim().Length == 0) return Refuse("Pick where to save the APK.");
        try { Path.GetFullPath(mOutput.Text.Trim()); }
        catch (Exception e) {
            if (e is ArgumentException || e is NotSupportedException || e is PathTooLongException) return Refuse("The folder to save the APK in isn't a valid path:\n\n" + mOutput.Text);
            throw;
        }
        if (!File.Exists(Path.Combine(mRepo, ".venv", "Scripts", "python.exe")) && !PythonWorks("py", "-3 --version") && !PythonWorks("python", "--version")) {
            if (MessageBox.Show(Window, "Python 3 is needed to build. Open python.org to get it?\n\nTick \"Add python.exe to PATH\" while installing, then open this program again.",
                                Window.Title, MessageBoxButton.YesNo, MessageBoxImage.Warning) == MessageBoxResult.Yes)
                Process.Start("https://www.python.org/downloads/");
            return false;
        }
        long free = new DriveInfo(Path.GetPathRoot(mRepo)).AvailableFreeSpace;
        if (free < NeededFreeBytes &&
            MessageBox.Show(Window, "A first build needs about 15 GB free on this drive, and it has " + free / (1L << 30) + " GB. Build anyway?",
                            Window.Title, MessageBoxButton.YesNo, MessageBoxImage.Warning) != MessageBoxResult.Yes)
            return false;
        if (SdkNeedsInstall()) {
            var r = MessageBox.Show(Window, "The build will install Google's Android SDK, which has its own licence terms:\n\n" + SdkTermsUrl +
                                            "\n\nChoose Yes to accept them for this build. Choose No to stop, or Cancel to stop and read them in your browser.",
                                    "Android SDK licence", MessageBoxButton.YesNoCancel, MessageBoxImage.Question, MessageBoxResult.No);
            if (r == MessageBoxResult.Cancel) Process.Start(SdkTermsUrl);
            if (r != MessageBoxResult.Yes) return false;
            acceptLicenses = true;
        }
        return true;
    }

    // ---------------------------------------------------------------- running build.ps1

    static string Quote(string s) { return "\"" + s.TrimEnd('\\') + "\""; }

    void MakeStages(bool install) {
        mStageList.Clear();
        mStages.Children.Clear();
        mStageList.Add(new Stage("Build tools", "Getting the build tools"));
        mStageList.Add(new Stage("Ghidra", "Analysing your game"));
        mStageList.Add(new Stage("Translating", "Translating the game's code"));
        mStageList.Add(new Stage("Bundling", "Packing the game files"));
        mStageList.Add(new Stage("Building the APK", "Compiling the APK"));
        if (install) mStageList.Add(new Stage("Phone", "Installing on your phone"));
        foreach (var s in mStageList) {
            var row = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 0, 0, 10) };
            s.Icon = new TextBlock { Width = 26, FontSize = 15, Foreground = Muted, Text = "\u25CB" };
            s.Label = new TextBlock { FontSize = 14, Foreground = Muted, Text = s.Title };
            row.Children.Add(s.Icon);
            row.Children.Add(s.Label);
            mStages.Children.Add(row);
        }
        mCurrentStage = -1;
    }

    void EnterStage(int index) {
        for (int i = 0; i < mStageList.Count; i++) {
            var s = mStageList[i];
            if (i < index) { s.Icon.Text = "\u2713"; s.Icon.Foreground = Good; s.Label.Foreground = Text; s.Label.FontWeight = FontWeights.Normal; }
            else if (i == index) { s.Icon.Text = "\u25B6"; s.Icon.Foreground = Good; s.Label.Foreground = Text; s.Label.FontWeight = FontWeights.SemiBold; }
        }
        mCurrentStage = index;
        mStageTitle.Text = mStageList[index].Title + "...";
        mBar.IsIndeterminate = true;
        mPercent.Text = "";
    }

    void StartBuild() {
        bool accept;
        if (!ReadyToBuild(out accept)) return;
        SaveSettings();
        bool install = mInstall.IsChecked == true;
        var args = new List<string> {
            "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", Quote(Path.Combine(mRepo, "android", "build.ps1")),
            "-Bundle", "-GameDir", Quote(mGame.Text.Trim()), "-OutputDir", Quote(mOutput.Text.Trim()) };
        if (mRosters.IsChecked == true) args.Add("-Rosters");
        if (install) args.Add("-Install");
        if (accept) args.Add("-AcceptSdkLicenses");

        var psi = new ProcessStartInfo("powershell.exe", string.Join(" ", args)) {
            UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true,
            WorkingDirectory = mRepo };
        mLog.Clear();
        mLogFile = new StreamWriter(Path.Combine(mRepo, "build.log"), false) { AutoFlush = true };
        mCancelled = false;
        mLastError = null;
        mProc = new Process { StartInfo = psi, EnableRaisingEvents = true };
        mProc.OutputDataReceived += OnLine;
        mProc.ErrorDataReceived += OnLine;
        mProc.Exited += delegate { Window.Dispatcher.BeginInvoke((Action)Finished); };
        mProc.Start();
        mProc.BeginOutputReadLine();
        mProc.BeginErrorReadLine();

        MakeStages(install);
        mStarted = DateTime.Now;
        mElapsed.Text = "0:00:00";
        mTimer.Start();
        mStageTitle.Text = "Starting...";
        mBar.IsIndeterminate = true;
        mPercent.Text = "";
        ShowDetails(false);
        SetBuildingButtons(true);
        Show(mBuilding);
    }

    void SetBuildingButtons(bool running) {
        mCancel.Visibility = running ? Visibility.Visible : Visibility.Collapsed;
        mCancel.IsEnabled = running;
        Find<Button>("LogBack").Visibility = running ? Visibility.Collapsed : Visibility.Visible;
    }

    void ShowDetails(bool show) {
        mLogPanel.Visibility = show ? Visibility.Visible : Visibility.Collapsed;
        mDetails.Content = show ? "Hide details" : "Show details";
    }

    void OnLine(object sender, DataReceivedEventArgs e) {
        if (e.Data == null) return;
        string line = e.Data;
        Window.Dispatcher.BeginInvoke((Action)delegate { HandleLine(line); });
    }

    void HandleLine(string line) {
        if (mLogFile != null) mLogFile.WriteLine(line);
        if (line.StartsWith("== "))
            for (int i = 0; i < mStageList.Count; i++)
                if (line.Substring(3).StartsWith(mStageList[i].Key)) EnterStage(i);
        if (line.StartsWith("ERROR:")) mLastError = line.Substring(6).Trim();
        else if (line.StartsWith("Exception ") && mLastError == null) mLastError = line.Trim();   // PowerShell's own errors
        if (mLog.Text.Length > LogLimit) mLog.Text = mLog.Text.Substring(LogLimit / 2);
        mLog.AppendText(line + "\n");
        if (mLogPanel.Visibility == Visibility.Visible) mLog.ScrollToEnd();
    }

    // Gradle doesn't report compile progress, so count the translated game's object files as they appear
    void ShowCompileProgress() {
        if (mCurrentStage < 0 || mStageList[mCurrentStage].Key != "Building the APK") return;
        string generated = Path.Combine(mRepo, "out_all"), objects = Path.Combine(mRepo, "android", "app", ".cxx");
        if (!Directory.Exists(generated) || !Directory.Exists(objects)) return;
        double total = Directory.GetFiles(generated, "*.c").Length, done = 0;
        foreach (var f in Directory.EnumerateFiles(objects, "*.c.o", SearchOption.AllDirectories))
            if (f.Contains("out_all")) done++;
        if (total == 0) return;
        double percent = Math.Min(100, 100 * done / total);
        mBar.IsIndeterminate = false;
        mBar.Value = percent;
        mPercent.Text = Math.Floor(percent) + "%";
    }

    void CancelBuild() {
        if (mProc == null || mProc.HasExited) return;
        mCancelled = true;
        mCancel.IsEnabled = false;
        mStageTitle.Text = "Cancelling...";
        KillBuild();
    }

    void KillBuild() {
        var psi = new ProcessStartInfo("taskkill", "/T /F /PID " + mProc.Id) { UseShellExecute = false, CreateNoWindow = true };
        using (var p = Process.Start(psi)) p.WaitForExit();
    }

    void Finished() {
        if (mProc == null) return;
        mProc.WaitForExit();    // lets the last output lines arrive
        int code = mProc.ExitCode;
        mProc = null;
        mTimer.Stop();
        mBar.IsIndeterminate = false;
        // output handlers queued before this one have run; anything later still reaches the screen log
        Window.Dispatcher.BeginInvoke((Action)delegate { if (mLogFile != null) { mLogFile.Close(); mLogFile = null; } }, DispatcherPriority.Background);
        if (mCancelled) {
            mInfo.Text = "Build cancelled. Finished steps are kept, so the next build carries on from them.";
            mInfo.Foreground = Warn;
            Show(mSetup);
        } else if (code == 0 && File.Exists(ApkPath)) {
            double gb = new FileInfo(ApkPath).Length / (double)(1L << 30);
            mDoneTitle.Text = "Your APK is ready";
            mDoneText.Text = ApkPath + "\n" + gb.ToString("0.0") + " GB, built in " + mElapsed.Text + "\n\n" +
                (mInstall.IsChecked == true ? "It's installed on your phone. Open Cricket 07 there; the first launch unpacks the game files."
                 : "Copy it to your phone and open it there. Android will ask you to allow installing from that source. The first launch unpacks the game files, which needs about 1.2 GB free.");
            Show(mDone);
        } else {
            mFailText.Text = (mLastError ?? "The build stopped unexpectedly.") + "\n\nThe full log is saved as build.log in the project folder.";
            Show(mFailed);
        }
    }

    void OnClosing(object sender, System.ComponentModel.CancelEventArgs e) {
        if (mProc == null || mProc.HasExited) return;
        if (MessageBox.Show(Window, "A build is running. Stop it and close?", Window.Title, MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) {
            e.Cancel = true;
            return;
        }
        KillBuild();
    }
}

static class Ui {
    public const string Xaml = @"
<Window xmlns='http://schemas.microsoft.com/winfx/2006/xaml/presentation' xmlns:x='http://schemas.microsoft.com/winfx/2006/xaml'
        Title='Cricket 07 for Android - Builder' Width='820' Height='680' MinWidth='680' MinHeight='620'
        WindowStartupLocation='CenterScreen' Background='#10201A' FontFamily='Segoe UI' Foreground='#EDF4EF' TextOptions.TextFormattingMode='Display'>
  <Window.Resources>
    <SolidColorBrush x:Key='Accent' Color='#2FA866'/>
    <SolidColorBrush x:Key='AccentHover' Color='#39BE76'/>
    <SolidColorBrush x:Key='Card' Color='#182C24'/>
    <SolidColorBrush x:Key='Line' Color='#2C4639'/>
    <SolidColorBrush x:Key='Muted' Color='#8FA69A'/>
    <Style x:Key='BaseButton' TargetType='Button'>
      <Setter Property='Foreground' Value='#EDF4EF'/>
      <Setter Property='Background' Value='#23392F'/>
      <Setter Property='Padding' Value='16,8'/>
      <Setter Property='FontSize' Value='13'/>
      <Setter Property='Cursor' Value='Hand'/>
      <Setter Property='Focusable' Value='True'/>
      <Setter Property='Template'>
        <Setter.Value>
          <ControlTemplate TargetType='Button'>
            <Border x:Name='B' Background='{TemplateBinding Background}' CornerRadius='6' Padding='{TemplateBinding Padding}'>
              <ContentPresenter HorizontalAlignment='Center' VerticalAlignment='Center'/>
            </Border>
            <ControlTemplate.Triggers>
              <Trigger Property='IsMouseOver' Value='True'><Setter TargetName='B' Property='Opacity' Value='0.85'/></Trigger>
              <Trigger Property='IsPressed' Value='True'><Setter TargetName='B' Property='Opacity' Value='0.7'/></Trigger>
              <Trigger Property='IsEnabled' Value='False'><Setter TargetName='B' Property='Opacity' Value='0.4'/></Trigger>
              <Trigger Property='IsKeyboardFocused' Value='True'><Setter TargetName='B' Property='Opacity' Value='0.85'/></Trigger>
            </ControlTemplate.Triggers>
          </ControlTemplate>
        </Setter.Value>
      </Setter>
    </Style>
    <Style x:Key='Primary' TargetType='Button' BasedOn='{StaticResource BaseButton}'>
      <Setter Property='Background' Value='{StaticResource Accent}'/>
      <Setter Property='FontSize' Value='15'/>
      <Setter Property='FontWeight' Value='SemiBold'/>
      <Setter Property='Padding' Value='32,11'/>
    </Style>
    <Style x:Key='CardBox' TargetType='Border'>
      <Setter Property='Background' Value='{StaticResource Card}'/>
      <Setter Property='BorderBrush' Value='{StaticResource Line}'/>
      <Setter Property='BorderThickness' Value='1'/>
      <Setter Property='CornerRadius' Value='8'/>
      <Setter Property='Padding' Value='18,14'/>
      <Setter Property='Margin' Value='0,0,0,12'/>
    </Style>
    <Style x:Key='Heading' TargetType='TextBlock'>
      <Setter Property='FontSize' Value='14'/>
      <Setter Property='FontWeight' Value='SemiBold'/>
      <Setter Property='Margin' Value='0,0,0,8'/>
    </Style>
    <Style TargetType='TextBox'>
      <Setter Property='Background' Value='#10201A'/>
      <Setter Property='Foreground' Value='#EDF4EF'/>
      <Setter Property='CaretBrush' Value='#EDF4EF'/>
      <Setter Property='BorderBrush' Value='{StaticResource Line}'/>
      <Setter Property='Padding' Value='8,7'/>
      <Setter Property='FontSize' Value='13'/>
      <Setter Property='VerticalContentAlignment' Value='Center'/>
    </Style>
    <Style TargetType='CheckBox'>
      <Setter Property='Foreground' Value='#EDF4EF'/>
      <Setter Property='Cursor' Value='Hand'/>
      <Setter Property='Template'>
        <Setter.Value>
          <ControlTemplate TargetType='CheckBox'>
            <StackPanel Orientation='Horizontal' Background='Transparent'>
              <Border x:Name='Box' Width='20' Height='20' CornerRadius='4' BorderThickness='1' BorderBrush='#47665A' Background='#10201A' VerticalAlignment='Top' Margin='0,1,11,0'>
                <TextBlock x:Name='Tick' Text='&#x2713;' Foreground='White' FontSize='13' FontWeight='Bold' HorizontalAlignment='Center' VerticalAlignment='Center' Visibility='Collapsed'/>
              </Border>
              <ContentPresenter/>
            </StackPanel>
            <ControlTemplate.Triggers>
              <Trigger Property='IsChecked' Value='True'>
                <Setter TargetName='Box' Property='Background' Value='{StaticResource Accent}'/>
                <Setter TargetName='Box' Property='BorderBrush' Value='{StaticResource Accent}'/>
                <Setter TargetName='Tick' Property='Visibility' Value='Visible'/>
              </Trigger>
              <Trigger Property='IsKeyboardFocused' Value='True'><Setter TargetName='Box' Property='BorderBrush' Value='#EDF4EF'/></Trigger>
            </ControlTemplate.Triggers>
          </ControlTemplate>
        </Setter.Value>
      </Setter>
    </Style>
    <Style TargetType='ProgressBar'>
      <Setter Property='Height' Value='8'/>
      <Setter Property='Background' Value='#23392F'/>
      <Setter Property='Foreground' Value='{StaticResource Accent}'/>
      <Setter Property='BorderThickness' Value='0'/>
    </Style>
  </Window.Resources>
  <DockPanel>
    <Border DockPanel.Dock='Top' Padding='26,18'>
      <Border.Background>
        <LinearGradientBrush StartPoint='0,0' EndPoint='1,0'><GradientStop Color='#14573A' Offset='0'/><GradientStop Color='#10201A' Offset='1'/></LinearGradientBrush>
      </Border.Background>
      <StackPanel>
        <TextBlock FontSize='25' FontWeight='Bold'>Cricket 07 <Run FontWeight='Light' Foreground='#9FDDB9'>for Android</Run></TextBlock>
        <TextBlock Margin='0,3,0,0' FontSize='13' Foreground='#B5CDBF'>Turns your copy of the PC game into an app for your phone</TextBlock>
      </StackPanel>
    </Border>
    <Grid Margin='26,18,26,20'>

      <Grid x:Name='SetupView'>
        <Grid.RowDefinitions><RowDefinition Height='*'/><RowDefinition Height='Auto'/></Grid.RowDefinitions>
        <ScrollViewer VerticalScrollBarVisibility='Auto'>
          <StackPanel>
            <Border Style='{StaticResource CardBox}'>
              <StackPanel>
                <TextBlock Style='{StaticResource Heading}' Text='1.  Your game'/>
                <Grid>
                  <Grid.ColumnDefinitions><ColumnDefinition Width='*'/><ColumnDefinition Width='Auto'/></Grid.ColumnDefinitions>
                  <TextBox x:Name='Game' AllowDrop='True'/>
                  <Button x:Name='BrowseGame' Grid.Column='1' Margin='8,0,0,0' Content='Browse...' Style='{StaticResource BaseButton}'/>
                </Grid>
                <TextBlock x:Name='GameStatus' Margin='0,8,0,0' FontSize='12.5' TextWrapping='Wrap'/>
              </StackPanel>
            </Border>
            <Border Style='{StaticResource CardBox}'>
              <StackPanel>
                <TextBlock Style='{StaticResource Heading}' Text='2.  Where to save the APK'/>
                <Grid>
                  <Grid.ColumnDefinitions><ColumnDefinition Width='*'/><ColumnDefinition Width='Auto'/></Grid.ColumnDefinitions>
                  <TextBox x:Name='Output'/>
                  <Button x:Name='BrowseOutput' Grid.Column='1' Margin='8,0,0,0' Content='Browse...' Style='{StaticResource BaseButton}'/>
                </Grid>
              </StackPanel>
            </Border>
            <Border Style='{StaticResource CardBox}' Margin='0'>
              <StackPanel>
                <TextBlock Style='{StaticResource Heading}' Text='3.  Options'/>
                <CheckBox x:Name='Rosters' IsChecked='True' Margin='0,0,0,12'>
                  <StackPanel>
                    <TextBlock FontSize='13.5' Text='Update to 2026 teams and players'/>
                    <TextBlock FontSize='12' Foreground='{StaticResource Muted}' TextWrapping='Wrap' Text='National sides, counties, IPL, PSL, Big Bash and CPL. Your game files aren&#39;t changed.'/>
                  </StackPanel>
                </CheckBox>
                <CheckBox x:Name='Install'>
                  <StackPanel>
                    <TextBlock FontSize='13.5' Text='Install on my phone when finished'/>
                    <TextBlock FontSize='12' Foreground='{StaticResource Muted}' TextWrapping='Wrap' Text='Connect the phone by USB with USB debugging turned on.'/>
                  </StackPanel>
                </CheckBox>
              </StackPanel>
            </Border>
          </StackPanel>
        </ScrollViewer>
        <Grid Grid.Row='1' Margin='0,16,0,0'>
          <Grid.ColumnDefinitions><ColumnDefinition Width='*'/><ColumnDefinition Width='Auto'/></Grid.ColumnDefinitions>
          <TextBlock x:Name='Info' VerticalAlignment='Center' Margin='0,0,16,0' FontSize='12.5' Foreground='{StaticResource Muted}' TextWrapping='Wrap'
                     Text='The first build downloads about 4 GB of tools and takes about an hour. Later builds are much faster.'/>
          <Button x:Name='BuildButton' Grid.Column='1' Content='Build APK' Style='{StaticResource Primary}' IsDefault='True'/>
        </Grid>
      </Grid>

      <Grid x:Name='BuildView' Visibility='Collapsed'>
        <Grid.RowDefinitions><RowDefinition Height='Auto'/><RowDefinition Height='Auto'/><RowDefinition Height='*'/><RowDefinition Height='Auto'/></Grid.RowDefinitions>
        <Grid Margin='0,0,0,10'>
          <Grid.ColumnDefinitions><ColumnDefinition Width='*'/><ColumnDefinition Width='Auto'/></Grid.ColumnDefinitions>
          <TextBlock x:Name='StageTitle' FontSize='19' FontWeight='SemiBold' TextTrimming='CharacterEllipsis'/>
          <StackPanel Grid.Column='1' Orientation='Horizontal' VerticalAlignment='Bottom'>
            <TextBlock x:Name='Percent' FontSize='14' FontWeight='SemiBold' Margin='0,0,12,0'/>
            <TextBlock x:Name='Elapsed' FontSize='14' Foreground='{StaticResource Muted}'/>
          </StackPanel>
        </Grid>
        <ProgressBar x:Name='Bar' Grid.Row='1' Minimum='0' Maximum='100' Margin='0,0,0,16'/>
        <Grid Grid.Row='2'>
          <Grid.RowDefinitions><RowDefinition Height='Auto'/><RowDefinition Height='*'/></Grid.RowDefinitions>
          <Border Style='{StaticResource CardBox}'>
            <StackPanel x:Name='Stages' Margin='0,4,0,-6'/>
          </Border>
          <Border x:Name='LogPanel' Grid.Row='1' Style='{StaticResource CardBox}' Padding='2' Margin='0' Visibility='Collapsed'>
            <TextBox x:Name='Log' IsReadOnly='True' BorderThickness='0' Background='Transparent' Padding='8' FontFamily='Consolas' FontSize='11.5'
                     VerticalScrollBarVisibility='Auto' HorizontalScrollBarVisibility='Auto' VerticalContentAlignment='Top' TextWrapping='NoWrap' AcceptsReturn='True'/>
          </Border>
        </Grid>
        <StackPanel Grid.Row='3' Orientation='Horizontal' HorizontalAlignment='Right' Margin='0,14,0,0'>
          <Button x:Name='CopyLog' Content='Copy log' Style='{StaticResource BaseButton}' Margin='0,0,8,0'/>
          <Button x:Name='Details' Content='Show details' Style='{StaticResource BaseButton}' Margin='0,0,8,0'/>
          <Button x:Name='LogBack' Content='Back' Style='{StaticResource BaseButton}' Visibility='Collapsed'/>
          <Button x:Name='Cancel' Content='Cancel' Style='{StaticResource BaseButton}'/>
        </StackPanel>
      </Grid>

      <Grid x:Name='DoneView' Visibility='Collapsed'>
        <StackPanel VerticalAlignment='Center' HorizontalAlignment='Center' MaxWidth='600'>
          <Border Width='72' Height='72' CornerRadius='36' Background='{StaticResource Accent}' HorizontalAlignment='Center' Margin='0,0,0,18'>
            <TextBlock Text='&#x2713;' FontSize='38' FontWeight='Bold' Foreground='White' HorizontalAlignment='Center' VerticalAlignment='Center'/>
          </Border>
          <TextBlock x:Name='DoneTitle' FontSize='23' FontWeight='SemiBold' HorizontalAlignment='Center' Margin='0,0,0,12'/>
          <TextBlock x:Name='DoneText' FontSize='13.5' Foreground='#B5CDBF' TextWrapping='Wrap' TextAlignment='Center'/>
          <StackPanel Orientation='Horizontal' HorizontalAlignment='Center' Margin='0,24,0,0'>
            <Button x:Name='OpenFolder' Content='Show the APK' Style='{StaticResource Primary}' Margin='0,0,10,0'/>
            <Button x:Name='DoneBack' Content='Build again' Style='{StaticResource BaseButton}' Padding='20,11'/>
          </StackPanel>
        </StackPanel>
      </Grid>

      <Grid x:Name='FailView' Visibility='Collapsed'>
        <StackPanel VerticalAlignment='Center' HorizontalAlignment='Center' MaxWidth='600'>
          <Border Width='72' Height='72' CornerRadius='36' Background='#C4514E' HorizontalAlignment='Center' Margin='0,0,0,18'>
            <TextBlock Text='!' FontSize='38' FontWeight='Bold' Foreground='White' HorizontalAlignment='Center' VerticalAlignment='Center'/>
          </Border>
          <TextBlock Text='The build didn&#39;t finish' FontSize='23' FontWeight='SemiBold' HorizontalAlignment='Center' Margin='0,0,0,12'/>
          <TextBlock x:Name='FailText' FontSize='13.5' Foreground='#B5CDBF' TextWrapping='Wrap' TextAlignment='Center'/>
          <StackPanel Orientation='Horizontal' HorizontalAlignment='Center' Margin='0,24,0,0'>
            <Button x:Name='FailLog' Content='See the log' Style='{StaticResource Primary}' Margin='0,0,10,0'/>
            <Button x:Name='FailBack' Content='Back' Style='{StaticResource BaseButton}' Padding='20,11'/>
          </StackPanel>
        </StackPanel>
      </Grid>
    </Grid>
  </DockPanel>
</Window>";
}
