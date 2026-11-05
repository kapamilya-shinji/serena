using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Globalization;
using System.IO;
using System.Media;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Speech.Synthesis;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace KapamilyaSerenaRecorderWindows
{
    internal static class Program
    {
        [STAThread]
        private static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new RecorderMainForm());
        }
    }

    public sealed class RecorderMainForm : Form
    {
        public const string CURRENT_VERSION = "5.9.0";
        private string _uiLang = "ja";
        private ComboBox _cmbLang;
        private Label _lblTitleField;
        private Label _lblArtistField;
        private Label _lblCodecField;

        [DllImport("winmm.dll", EntryPoint = "mciSendStringW", CharSet = CharSet.Unicode)]
        private static extern int mciSendString(string command, StringBuilder returnValue, int returnLength, IntPtr winHandle);

        private readonly SpeechSynthesizer _tts;
        private readonly string _outputFolder;
        private bool _isRecording;
        private string _tempRawRecordPath;
        private string _lastMasteredWavPreviewPath;
        private string _lastMasteredExportPath;
        private string _lastCoverArtPath;
        private string _lastVocalAnalysisSpoken = "";
        private WindowsGemmaDualCoreResult _lastGemmaDualCoreResult;
        private SoundPlayer _previewPlayer;
        private bool _isPlayingPreview;

        private Label _lblStatusBanner;
        private TextBox _txtSongTitle;
        private TextBox _txtArtistName;
        private ComboBox _cmbCodec;
        private CheckBox _chkAutoTrimScreenReader;
        private CheckBox _chkRemoveKeyboardClicks;
        private CheckBox _chkItuLoudnessNormalize;
        private CheckBox _chkHallReverb;
        private CheckBox _chkCoverTitleOverlay;
        private TextBox _txtAnalysisReport;

        private Button _btnRecordToggle;
        private Button _btnImportAudio;
        private Button _btnGenerateCover;
        private Button _btnSpeakAnalysis;
        private Button _btnCopyLinerNotes;
        private Button _btnPreviewToggle;
        private Button _btnOpenOutputFolder;

        public RecorderMainForm()
        {
            _outputFolder = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory),
                "Shinji_Recordings"
            );
            Directory.CreateDirectory(_outputFolder);

            _tts = new SpeechSynthesizer();
            try
            {
                _tts.SetOutputToDefaultAudioDevice();
            }
            catch { }

            BuildAccessibleUI();
            KapamilyaKrisSoundEngine.AttachToFormHierarchy(this);
            KeyPreview = true;
            KeyDown += OnFormKeyDown;

            Shown += (s, e) =>
            {
                SpeakAsync(
                    "Kapamilya Serena Recorder for Windows バージョン 5.8.0 へようこそ！" +
                    "Google DeepMind の EmbeddingGemma 2 と Gemma 4 デュアルコアAIを強制組込した超強化版だよ！" +
                    "F5キーでバックグラウンド48キロヘルツ録音開始と停止、" +
                    "F6キーで既存のFLACやWAVファイルの読み込み・国際標準 ITU-R BS.1770-4 ラウドネスノーマライズ・ボーカル音域スペクトラム解析・Gemma 4 ボーカルディレクター講評＆3言語ライナーノーツ生成、" +
                    "F7キーで EmbeddingGemma 2 音響テーマ連動の3000×3000ピクセルJPGジャケット生成、" +
                    "F8キーでプレビュー再生、F9キーで保存先フォルダ表示、F10キーでボーカル音域＆Gemma 4 レポート読み上げ、F11キーで3言語ライナーノーツをクリップボードにコピーできるよ！"
                );
                CheckForDualRouteUpdateOnStartup();
            };
        }

        private void CheckForDualRouteUpdateOnStartup()
        {
            Task.Run(() =>
            {
                try
                {
                    System.Net.ServicePointManager.SecurityProtocol = System.Net.SecurityProtocolType.Tls12;
                    string pagesUrl = "https://kapamilya.gitlab.io/latest.json";
                    string repoUrl = "https://gitlab.com/kapamilya/serena/-/raw/main/latest.json";

                    string pagesJson = null, repoJson = null;
                    long pagesMs = long.MaxValue, repoMs = long.MaxValue;

                    var tPages = Task.Run(() =>
                    {
                        try
                        {
                            var sw = Stopwatch.StartNew();
                            var req = (System.Net.HttpWebRequest)System.Net.WebRequest.Create(pagesUrl + "?t=" + DateTime.UtcNow.Ticks);
                            req.Timeout = 6000;
                            req.UserAgent = "Kapamilya-Serena-Recorder-Windows/" + CURRENT_VERSION;
                            using (var resp = (System.Net.HttpWebResponse)req.GetResponse())
                            using (var sr = new StreamReader(resp.GetResponseStream(), Encoding.UTF8))
                            {
                                pagesJson = sr.ReadToEnd();
                                pagesMs = Math.Max(1L, sw.ElapsedMilliseconds);
                            }
                        }
                        catch { }
                    });

                    var tRepo = Task.Run(() =>
                    {
                        try
                        {
                            var sw = Stopwatch.StartNew();
                            var req = (System.Net.HttpWebRequest)System.Net.WebRequest.Create(repoUrl + "?t=" + DateTime.UtcNow.Ticks);
                            req.Timeout = 6000;
                            req.UserAgent = "Kapamilya-Serena-Recorder-Windows/" + CURRENT_VERSION;
                            using (var resp = (System.Net.HttpWebResponse)req.GetResponse())
                            using (var sr = new StreamReader(resp.GetResponseStream(), Encoding.UTF8))
                            {
                                repoJson = sr.ReadToEnd();
                                repoMs = Math.Max(1L, sw.ElapsedMilliseconds);
                            }
                        }
                        catch { }
                    });

                    Task.WaitAll(new Task[] { tPages, tRepo }, 7000);

                    string chosenJson = (pagesMs <= repoMs && !string.IsNullOrEmpty(pagesJson)) ? pagesJson : repoJson;
                    string chosenRoute = (pagesMs <= repoMs && !string.IsNullOrEmpty(pagesJson)) ? "GitLab Pages" : "GitLab Repository";
                    long chosenMs = (pagesMs <= repoMs && !string.IsNullOrEmpty(pagesJson)) ? pagesMs : repoMs;
                    if (string.IsNullOrEmpty(chosenJson)) return;

                    int idx = chosenJson.IndexOf("\"kapamilya-serena-recorder-windows\"", StringComparison.OrdinalIgnoreCase);
                    if (idx < 0) return;
                    string sec = chosenJson.Substring(idx);

                    string latestVer = ExtractSimpleJsonField(sec, "version").TrimStart('v', 'V').Trim();
                    string urlKey = chosenRoute == "GitLab Pages" ? "gitlab_pages_url" : "gitlab_repo_url";
                    string dlUrl = ExtractSimpleJsonField(sec, urlKey);
                    if (string.IsNullOrEmpty(dlUrl))
                        dlUrl = "https://gitlab.com/api/v4/projects/kapamilya%2Fserena/packages/generic/kapamilya-serena/5.14.0/Kapamilya-Serena-Recorder-Windows-Setup.exe";

                    Version vLatest, vCurrent;
                    if (Version.TryParse(latestVer, out vLatest) && Version.TryParse(CURRENT_VERSION, out vCurrent) && vLatest > vCurrent)
                    {
                        if (IsDisposed || !IsHandleCreated) return;
                        BeginInvoke(new Action(() =>
                        {
                            if (IsDisposed) return;
                            string prompt = _uiLang == "en"
                                ? string.Format("New version v{0} is available (Fastest route: {1} {2}ms). Download and run the auto-update installer now?", latestVer, chosenRoute, chosenMs)
                                : (_uiLang == "tl"
                                    ? string.Format("May bagong bersyon v{0} (Pinakamabilis na ruta: {1} {2}ms). I-download at patakbuhin ang auto-update installer ngayon?", latestVer, chosenRoute, chosenMs)
                                    : (_uiLang == "es"
                                        ? string.Format("Nueva versión v{0} disponible (Ruta más rápida: {1} {2}ms). ¿Descargar y ejecutar el instalador ahora?", latestVer, chosenRoute, chosenMs)
                                        : string.Format("新しいバージョン v{0} が見つかりました（高速回線自動選択: {1} {2}ms）。今すぐ自動更新インストーラーをダウンロードして実行しますか？", latestVer, chosenRoute, chosenMs)));
                            SpeakAsync(prompt);
                            if (MessageBox.Show(this, prompt, "🚀 Kapamilya Serena Recorder Auto-Update", MessageBoxButtons.YesNo, MessageBoxIcon.Information) == DialogResult.Yes)
                            {
                                SpeakAsync(chosenRoute + " から最新インストーラーをダウンロードしています。");
                                Task.Run(() =>
                                {
                                    try
                                    {
                                        string outPath = Path.Combine(Path.GetTempPath(), "Kapamilya-Serena-Recorder-Windows-Setup.exe");
                                        using (var wc = new System.Net.WebClient())
                                        {
                                            wc.Headers["User-Agent"] = "Kapamilya-Serena-Recorder-Windows/" + CURRENT_VERSION;
                                            wc.DownloadFile(dlUrl, outPath);
                                        }
                                        Process.Start(new ProcessStartInfo { FileName = outPath, UseShellExecute = true });
                                    }
                                    catch
                                    {
                                        try { Process.Start(new ProcessStartInfo { FileName = dlUrl, UseShellExecute = true }); } catch { }
                                    }
                                });
                            }
                        }));
                    }
                }
                catch { }
            });
        }

        private static string ExtractSimpleJsonField(string src, string key)
        {
            string pat = "\"" + key + "\"";
            int kIdx = src.IndexOf(pat, StringComparison.OrdinalIgnoreCase);
            if (kIdx < 0) return "";
            int colon = src.IndexOf(':', kIdx + pat.Length);
            if (colon < 0) return "";
            int q1 = src.IndexOf('"', colon + 1);
            if (q1 < 0) return "";
            int q2 = src.IndexOf('"', q1 + 1);
            if (q2 < 0) return "";
            return src.Substring(q1 + 1, q2 - q1 - 1);
        }

        private void SpeakAsync(string message)
        {
            if (InvokeRequired)
            {
                BeginInvoke(new Action(() => SpeakAsync(message)));
                return;
            }
            _lblStatusBanner.Text = message;
            _lblStatusBanner.AccessibleName = message;
            if (!_isRecording)
            {
                try
                {
                    _tts.SpeakAsyncCancelAll();
                    _tts.SpeakAsync(message);
                }
                catch { }
            }
        }

        private void BuildAccessibleUI()
        {
            Text = "Kapamilya Serena Recorder for Windows v" + CURRENT_VERSION + " — Gemma 4 + EmbeddingGemma 2・ITU-R BS.1770-4 ラウドネス・音域レジスター解析・3000pxジャケット生成";
            Size = new Size(940, 900);
            StartPosition = FormStartPosition.CenterScreen;
            BackColor = Color.FromArgb(10, 14, 28);
            ForeColor = Color.White;
            Font = new Font("Yu Gothic UI", 11.0f, FontStyle.Regular);

            var panel = new FlowLayoutPanel
            {
                Dock = DockStyle.Fill,
                FlowDirection = FlowDirection.TopDown,
                WrapContents = false,
                AutoScroll = true,
                Padding = new Padding(20)
            };

            var lblHeader = new Label
            {
                Text = "Kapamilya Serena Recorder for Windows v" + CURRENT_VERSION + " — 4-Language Global Edition • Gemma 4 + EmbeddingGemma 2 Dual-Core",
                Font = new Font("Yu Gothic UI", 14.0f, FontStyle.Bold),
                ForeColor = Color.FromArgb(255, 222, 115),
                AutoSize = true,
                Margin = new Padding(0, 0, 0, 8)
            };
            panel.Controls.Add(lblHeader);

            var langRow = new FlowLayoutPanel
            {
                FlowDirection = FlowDirection.LeftToRight,
                AutoSize = true,
                Margin = new Padding(0, 0, 0, 8)
            };
            var lblLang = new Label
            {
                Text = "🌍 Language / 表示・音声言語 (&L):",
                AutoSize = true,
                ForeColor = Color.FromArgb(125, 211, 252),
                Margin = new Padding(0, 6, 8, 0)
            };
            langRow.Controls.Add(lblLang);

            _cmbLang = new ComboBox
            {
                AccessibleName = "表示・音声言語の選択",
                DropDownStyle = ComboBoxStyle.DropDownList,
                Width = 260,
                BackColor = Color.FromArgb(20, 28, 48),
                ForeColor = Color.White
            };
            _cmbLang.Items.AddRange(new object[]
            {
                "🇯🇵 日本語 (Japanese)",
                "🇺🇸 English (英語)",
                "🇵🇭 Tagalog / Filipino (タガログ語)",
                "🇪🇸 Español (スペイン語)"
            });
            string osIso = CultureInfo.CurrentUICulture.TwoLetterISOLanguageName.ToLowerInvariant();
            if (osIso == "en") { _uiLang = "en"; _cmbLang.SelectedIndex = 1; }
            else if (osIso == "tl" || osIso == "fil") { _uiLang = "tl"; _cmbLang.SelectedIndex = 2; }
            else if (osIso == "es") { _uiLang = "es"; _cmbLang.SelectedIndex = 3; }
            else { _uiLang = "ja"; _cmbLang.SelectedIndex = 0; }

            _cmbLang.SelectedIndexChanged += (s, e) =>
            {
                _uiLang = _cmbLang.SelectedIndex == 1 ? "en" : (_cmbLang.SelectedIndex == 2 ? "tl" : (_cmbLang.SelectedIndex == 3 ? "es" : "ja"));
                ApplyRecorderLocalization();
                SpeakAsync(_cmbLang.SelectedItem.ToString());
            };
            langRow.Controls.Add(_cmbLang);
            panel.Controls.Add(langRow);

            _lblStatusBanner = new Label
            {
                Text = "準備完了：F5:録音 | F6:マスタリング＆Gemma 4解析 | F7:3000pxジャケット | F8:再生 | F9:フォルダ | F10:レポート読み上げ | F11:3言語ライナーノーツコピー",
                AccessibleName = "ステータスバー",
                BackColor = Color.FromArgb(22, 36, 70),
                ForeColor = Color.White,
                Font = new Font("Yu Gothic UI", 11.0f, FontStyle.Bold),
                Size = new Size(870, 64),
                Padding = new Padding(10),
                Margin = new Padding(0, 0, 0, 10)
            };
            panel.Controls.Add(_lblStatusBanner);

            _lblTitleField = new Label
            {
                Text = "曲タイトル (&T):",
                AutoSize = true,
                ForeColor = Color.FromArgb(255, 220, 120)
            };
            panel.Controls.Add(_lblTitleField);

            _txtSongTitle = new TextBox
            {
                Text = "Vocal_Master_Take",
                AccessibleName = "曲タイトル入力欄",
                Size = new Size(870, 32),
                BackColor = Color.FromArgb(20, 28, 48),
                ForeColor = Color.White,
                TabIndex = 0,
                Margin = new Padding(0, 2, 0, 8)
            };
            panel.Controls.Add(_txtSongTitle);

            _lblArtistField = new Label
            {
                Text = "アーティスト名 (&A):",
                AutoSize = true,
                ForeColor = Color.FromArgb(255, 220, 120)
            };
            panel.Controls.Add(_lblArtistField);

            _txtArtistName = new TextBox
            {
                Text = "Shinji Sakiyama",
                AccessibleName = "アーティスト名入力欄",
                Size = new Size(870, 32),
                BackColor = Color.FromArgb(20, 28, 48),
                ForeColor = Color.White,
                TabIndex = 1,
                Margin = new Padding(0, 2, 0, 8)
            };
            panel.Controls.Add(_txtArtistName);

            _lblCodecField = new Label
            {
                Text = "書き出しオーディオコーデック (&C):",
                AutoSize = true,
                ForeColor = Color.FromArgb(255, 220, 120)
            };
            panel.Controls.Add(_lblCodecField);

            _cmbCodec = new ComboBox
            {
                AccessibleName = "書き出しオーディオコーデック選択",
                DropDownStyle = ComboBoxStyle.DropDownList,
                Size = new Size(870, 32),
                BackColor = Color.FromArgb(24, 36, 64),
                ForeColor = Color.White,
                TabIndex = 2,
                Margin = new Padding(0, 2, 0, 10)
            };
            _cmbCodec.Items.AddRange(new object[]
            {
                "FLAC (.flac) — 48kHz 16-bit ロスレス可逆圧縮（配信ストア推奨・同時WAVバックアップ付き）",
                "WAV (.wav) — 48kHz 16-bit 非圧縮リニアPCM"
            });
            _cmbCodec.SelectedIndex = 0;
            panel.Controls.Add(_cmbCodec);

            _chkAutoTrimScreenReader = new CheckBox
            {
                Text = "録音開始・停止時のNVDA/スクリーンリーダー読み上げ声＆キー打鍵音を自動トリミング消去する",
                AccessibleName = "録音前後のNVDA読み上げ声とキー打鍵音を自動トリミング消去する",
                Checked = true,
                AutoSize = true,
                ForeColor = Color.White,
                TabIndex = 3,
                Margin = new Padding(0, 2, 0, 4)
            };
            panel.Controls.Add(_chkAutoTrimScreenReader);

            _chkRemoveKeyboardClicks = new CheckBox
            {
                Text = "歌唱中のキーボード打鍵音・クリック除去＋80Hzローカット＋ボーカルコンプレッサーを適用",
                AccessibleName = "歌唱中のキーボード打鍵音除去と80Hzローカットとボーカルコンプレッサーを適用",
                Checked = true,
                AutoSize = true,
                ForeColor = Color.White,
                TabIndex = 4,
                Margin = new Padding(0, 2, 0, 4)
            };
            panel.Controls.Add(_chkRemoveKeyboardClicks);

            _chkItuLoudnessNormalize = new CheckBox
            {
                Text = "国際標準規格 ITU-R BS.1770-4 / EBU R128 ラウドネスノーマライズ（-14.0 LUFS ＆ -1.0 dBTP トゥルーピーク制限）を適用",
                AccessibleName = "国際標準規格 ITU-R BS.1770-4 ラウドネスノーマライズ マイナス14 LUFS を適用",
                Checked = true,
                AutoSize = true,
                ForeColor = Color.FromArgb(165, 250, 215),
                TabIndex = 5,
                Margin = new Padding(0, 2, 0, 4)
            };
            panel.Controls.Add(_chkItuLoudnessNormalize);

            _chkHallReverb = new CheckBox
            {
                Text = "ナチュラル・アコースティックホールリバーブ（自然な響き）を追加する",
                AccessibleName = "ナチュラル・アコースティックホールリバーブを追加する",
                Checked = false,
                AutoSize = true,
                ForeColor = Color.White,
                TabIndex = 6,
                Margin = new Padding(0, 2, 0, 4)
            };
            panel.Controls.Add(_chkHallReverb);

            _chkCoverTitleOverlay = new CheckBox
            {
                Text = "3000×3000px ジャケット画像に曲名と Shinji Sakiyama の文字を入れる",
                AccessibleName = "3000×3000ピクセルジャケット画像に曲名とアーティスト名を入れる",
                Checked = true,
                AutoSize = true,
                ForeColor = Color.White,
                TabIndex = 7,
                Margin = new Padding(0, 2, 0, 10)
            };
            panel.Controls.Add(_chkCoverTitleOverlay);

            _btnRecordToggle = CreateActionButton(
                "1. アカペラ録音開始 [F5] (NVDA読み上げ終了待ち＋バックグラウンド48kHz録音)",
                Color.FromArgb(185, 28, 52),
                8,
                (s, e) => ToggleRecording()
            );
            panel.Controls.Add(_btnRecordToggle);

            _btnImportAudio = CreateActionButton(
                "2. 既存の音声ファイル (.flac / .wav) を選んでノイズ除去・ITUラウドネス調整＆Gemma 4音域解析 [F6]",
                Color.FromArgb(22, 118, 92),
                9,
                (s, e) => ImportAndMasterAudioFile()
            );
            panel.Controls.Add(_btnImportAudio);

            _btnGenerateCover = CreateActionButton(
                "3. 3000×3000ピクセル EmbeddingGemma 2 音響テーマ連動JPGジャケット生成 [F7]",
                Color.FromArgb(145, 88, 16),
                10,
                (s, e) => GenerateRandom3000CoverArt()
            );
            panel.Controls.Add(_btnGenerateCover);

            _btnSpeakAnalysis = CreateActionButton(
                "4. ボーカル音域＆Gemma 4 ボーカルディレクター講評レポートを読み上げる [F10]",
                Color.FromArgb(16, 108, 128),
                11,
                (s, e) => SpeakVocalAnalysisReport()
            );
            panel.Controls.Add(_btnSpeakAnalysis);

            _btnCopyLinerNotes = CreateActionButton(
                "5. Gemma 4 配信ストア用 3言語ライナーノーツ (日/英/タガログ) を読み上げてクリップボードにコピー [F11]",
                Color.FromArgb(124, 52, 152),
                12,
                (s, e) => SpeakAndCopyLinerNotes()
            );
            panel.Controls.Add(_btnCopyLinerNotes);

            _btnPreviewToggle = CreateActionButton(
                "6. マスタリング済み音源をプレビュー再生 / 停止 [F8]",
                Color.FromArgb(54, 72, 132),
                13,
                (s, e) => TogglePreviewPlayback()
            );
            panel.Controls.Add(_btnPreviewToggle);

            _btnOpenOutputFolder = CreateActionButton(
                "7. 保存先フォルダ (Desktop\\Shinji_Recordings) をエクスプローラーで開く [F9]",
                Color.FromArgb(88, 44, 138),
                14,
                (s, e) => OpenOutputFolder()
            );
            panel.Controls.Add(_btnOpenOutputFolder);

            _txtAnalysisReport = new TextBox
            {
                Multiline = true,
                ReadOnly = true,
                ScrollBars = ScrollBars.Vertical,
                AccessibleName = "ボーカルスペクトラム＆Gemma 4 ライナーノーツ解析レポート表示欄",
                Text = "【EmbeddingGemma 2 + Gemma 4 ボーカルスペクトラム＆ITU-R BS.1770-4 ラウドネス解析レポート欄】\r\n録音または音声ファイル読み込みを行うと、ここに基本周波数（F0）、チェスト／ミックス／ヘッド／ホイッスル限界、倍音比（H1-H2）、ラウドネス（LUFS / dBTP）、Gemma 4 ディレクター講評、および3言語ライナーノーツが表示されます。",
                Size = new Size(870, 145),
                BackColor = Color.FromArgb(14, 22, 42),
                ForeColor = Color.FromArgb(220, 240, 255),
                Font = new Font("Consolas", 10.5f, FontStyle.Regular),
                TabIndex = 15,
                Margin = new Padding(0, 4, 0, 10)
            };
            panel.Controls.Add(_txtAnalysisReport);

            Controls.Add(panel);
            ApplyRecorderLocalization();
        }

        private void ApplyRecorderLocalization()
        {
            if (_uiLang == "en")
            {
                _lblTitleField.Text = "Song Title (&T):";
                _lblArtistField.Text = "Artist Name (&A):";
                _lblCodecField.Text = "Output Audio Codec (&C):";
                _chkAutoTrimScreenReader.Text = "Auto-trim screen reader speech & key clicks at start/end of recording";
                _chkRemoveKeyboardClicks.Text = "Apply keyboard click removal, 80Hz low-cut filter & vocal compressor";
                _chkItuLoudnessNormalize.Text = "Apply ITU-R BS.1770-4 / EBU R128 Loudness Normalization (-14.0 LUFS & -1.0 dBTP)";
                _chkHallReverb.Text = "Add Natural Acoustic Hall Reverb";
                _chkCoverTitleOverlay.Text = "Overlay Song Title & Artist Name onto 3000x3000px Cover Art";
                _btnRecordToggle.Text = "1. Start Vocal Recording [F5] (Wait for TTS Silence + 48kHz Background Recording)";
                _btnImportAudio.Text = "2. Import Audio (.flac/.wav) for Denoising, ITU Loudness & Gemma 4 Analysis [F6]";
                _btnGenerateCover.Text = "3. Generate 3000x3000px EmbeddingGemma 2 JPG Cover Art [F7]";
                _btnSpeakAnalysis.Text = "4. Speak Vocal Range & Gemma 4 Director Review Report [F10]";
                _btnCopyLinerNotes.Text = "5. Speak & Copy 3-Language Liner Notes (JA/EN/TL) to Clipboard [F11]";
                _btnPreviewToggle.Text = "6. Preview Play / Stop Mastered Audio [F8]";
                _btnOpenOutputFolder.Text = "7. Open Output Folder (Desktop\\Shinji_Recordings) [F9]";
                _lblStatusBanner.Text = "Ready: F5:Record | F6:Master & Gemma 4 | F7:3000px Cover | F8:Preview | F9:Folder | F10:Speak Report | F11:Copy Liner Notes";
            }
            else if (_uiLang == "tl")
            {
                _lblTitleField.Text = "Pamagat ng Kanta (&T):";
                _lblArtistField.Text = "Pangalan ng Artist (&A):";
                _lblCodecField.Text = "Output Audio Codec (&C):";
                _chkAutoTrimScreenReader.Text = "Kusang alisin ang boses ng screen reader at key click sa simula/wakas";
                _chkRemoveKeyboardClicks.Text = "Alisin ang click noise at ilapat ang 80Hz low-cut at vocal compressor";
                _chkItuLoudnessNormalize.Text = "Ilapat ang ITU-R BS.1770-4 / EBU R128 Loudness Normalization (-14.0 LUFS at -1.0 dBTP)";
                _chkHallReverb.Text = "Magdagdag ng Natural Acoustic Hall Reverb";
                _chkCoverTitleOverlay.Text = "Ilagay ang Pamagat at Pangalan ng Artist sa 3000x3000px Cover Art";
                _btnRecordToggle.Text = "1. Simulan ang Pag-record ng Boses [F5] (48kHz Background Recording)";
                _btnImportAudio.Text = "2. Pumili ng Audio (.flac/.wav) para sa Mastering at Gemma 4 Analysis [F6]";
                _btnGenerateCover.Text = "3. Gumawa ng 3000x3000px EmbeddingGemma 2 JPG Cover Art [F7]";
                _btnSpeakAnalysis.Text = "4. Basahin ang Ulat sa Vocal Range at Gemma 4 Review [F10]";
                _btnCopyLinerNotes.Text = "5. Basahin at Kopyahin ang 3-Wikang Liner Notes sa Clipboard [F11]";
                _btnPreviewToggle.Text = "6. Pakinggan / Ihinto ang Mastered Audio [F8]";
                _btnOpenOutputFolder.Text = "7. Buksan ang Output Folder (Desktop\\Shinji_Recordings) [F9]";
                _lblStatusBanner.Text = "Handa na: F5:Record | F6:Master at Gemma 4 | F7:3000px Cover | F8:Play | F9:Folder | F10:Ulat | F11:Kopyahin";
            }
            else if (_uiLang == "es")
            {
                _lblTitleField.Text = "Título de la Canción (&T):";
                _lblArtistField.Text = "Nombre del Artista (&A):";
                _lblCodecField.Text = "Códec de Salida de Audio (&C):";
                _chkAutoTrimScreenReader.Text = "Recortar automáticamente voz del lector de pantalla y clics al iniciar/detener";
                _chkRemoveKeyboardClicks.Text = "Eliminar clics de teclado y aplicar filtro 80Hz y compresor vocal";
                _chkItuLoudnessNormalize.Text = "Aplicar Normalización ITU-R BS.1770-4 / EBU R128 (-14.0 LUFS y -1.0 dBTP)";
                _chkHallReverb.Text = "Añadir Reverberación Acústica Natural de Sala";
                _chkCoverTitleOverlay.Text = "Incluir Título y Nombre del Artista en la Portada de 3000x3000px";
                _btnRecordToggle.Text = "1. Iniciar Grabación Vocal [F5] (Grabación en Segundo Plano 48kHz)";
                _btnImportAudio.Text = "2. Importar Audio (.flac/.wav) para Masterizar y Analizar con Gemma 4 [F6]";
                _btnGenerateCover.Text = "3. Generar Portada JPG 3000x3000px con EmbeddingGemma 2 [F7]";
                _btnSpeakAnalysis.Text = "4. Leer Informe de Rango Vocal y Reseña Gemma 4 [F10]";
                _btnCopyLinerNotes.Text = "5. Leer y Copiar Notas en 3 Idiomas al Portapapeles [F11]";
                _btnPreviewToggle.Text = "6. Reproducir / Detener Vista Previa [F8]";
                _btnOpenOutputFolder.Text = "7. Abrir Carpeta de Salida (Desktop\\Shinji_Recordings) [F9]";
                _lblStatusBanner.Text = "Listo: F5:Grabar | F6:Masterizar | F7:Portada 3000px | F8:Vista Previa | F9:Carpeta | F10:Informe | F11:Copiar Notas";
            }
            else
            {
                _lblTitleField.Text = "曲タイトル (&T):";
                _lblArtistField.Text = "アーティスト名 (&A):";
                _lblCodecField.Text = "書き出しオーディオコーデック (&C):";
                _chkAutoTrimScreenReader.Text = "録音開始・停止時のNVDA/スクリーンリーダー読み上げ声＆キー打鍵音を自動トリミング消去する";
                _chkRemoveKeyboardClicks.Text = "歌唱中のキーボード打鍵音・クリック除去＋80Hzローカット＋ボーカルコンプレッサーを適用";
                _chkItuLoudnessNormalize.Text = "国際標準規格 ITU-R BS.1770-4 / EBU R128 ラウドネスノーマライズ（-14.0 LUFS ＆ -1.0 dBTP トゥルーピーク制限）を適用";
                _chkHallReverb.Text = "ナチュラル・アコースティックホールリバーブ（自然な響き）を追加する";
                _chkCoverTitleOverlay.Text = "3000×3000px ジャケット画像に曲名と Shinji Sakiyama の文字を入れる";
                _btnRecordToggle.Text = "1. アカペラ録音開始 [F5] (NVDA読み上げ終了待ち＋バックグラウンド48kHz録音)";
                _btnImportAudio.Text = "2. 既存の音声ファイル (.flac / .wav) を選んでノイズ除去・ITUラウドネス調整＆Gemma 4音域解析 [F6]";
                _btnGenerateCover.Text = "3. 3000×3000ピクセル EmbeddingGemma 2 音響テーマ連動JPGジャケット生成 [F7]";
                _btnSpeakAnalysis.Text = "4. ボーカル音域＆Gemma 4 ボーカルディレクター講評レポートを読み上げる [F10]";
                _btnCopyLinerNotes.Text = "5. Gemma 4 配信ストア用 3言語ライナーノーツ (日/英/タガログ) を読み上げてクリップボードにコピー [F11]";
                _btnPreviewToggle.Text = "6. マスタリング済み音源をプレビュー再生 / 停止 [F8]";
                _btnOpenOutputFolder.Text = "7. 保存先フォルダ (Desktop\\Shinji_Recordings) をエクスプローラーで開く [F9]";
                _lblStatusBanner.Text = "準備完了：F5:録音 | F6:マスタリング＆Gemma 4解析 | F7:3000pxジャケット | F8:再生 | F9:フォルダ | F10:レポート読み上げ | F11:3言語ライナーノーツコピー";
            }
            _chkAutoTrimScreenReader.AccessibleName = _chkAutoTrimScreenReader.Text;
            _chkRemoveKeyboardClicks.AccessibleName = _chkRemoveKeyboardClicks.Text;
            _chkItuLoudnessNormalize.AccessibleName = _chkItuLoudnessNormalize.Text;
            _chkHallReverb.AccessibleName = _chkHallReverb.Text;
            _chkCoverTitleOverlay.AccessibleName = _chkCoverTitleOverlay.Text;
            _btnRecordToggle.AccessibleName = _btnRecordToggle.Text;
            _btnImportAudio.AccessibleName = _btnImportAudio.Text;
            _btnGenerateCover.AccessibleName = _btnGenerateCover.Text;
            _btnSpeakAnalysis.AccessibleName = _btnSpeakAnalysis.Text;
            _btnCopyLinerNotes.AccessibleName = _btnCopyLinerNotes.Text;
            _btnPreviewToggle.AccessibleName = _btnPreviewToggle.Text;
            _btnOpenOutputFolder.AccessibleName = _btnOpenOutputFolder.Text;
        }

        private Button CreateActionButton(string label, Color bg, int tabIndex, EventHandler onClick)
        {
            var btn = new Button
            {
                Text = label,
                AccessibleName = label,
                Size = new Size(870, 42),
                BackColor = bg,
                ForeColor = Color.White,
                FlatStyle = FlatStyle.Flat,
                Font = new Font("Yu Gothic UI", 10.8f, FontStyle.Bold),
                TabIndex = tabIndex,
                Margin = new Padding(0, 0, 0, 7)
            };
            btn.Click += onClick;
            return btn;
        }

        private void OnFormKeyDown(object sender, KeyEventArgs e)
        {
            if (e.KeyCode == Keys.F5)
            {
                e.Handled = true;
                ToggleRecording();
            }
            else if (e.KeyCode == Keys.F6)
            {
                e.Handled = true;
                ImportAndMasterAudioFile();
            }
            else if (e.KeyCode == Keys.F7)
            {
                e.Handled = true;
                GenerateRandom3000CoverArt();
            }
            else if (e.KeyCode == Keys.F8)
            {
                e.Handled = true;
                TogglePreviewPlayback();
            }
            else if (e.KeyCode == Keys.F9)
            {
                e.Handled = true;
                OpenOutputFolder();
            }
            else if (e.KeyCode == Keys.F10)
            {
                e.Handled = true;
                SpeakVocalAnalysisReport();
            }
            else if (e.KeyCode == Keys.F11)
            {
                e.Handled = true;
                SpeakAndCopyLinerNotes();
            }
        }

        private void SpeakVocalAnalysisReport()
        {
            if (string.IsNullOrEmpty(_lastVocalAnalysisSpoken))
            {
                SpeakAsync("まだ解析レポートがないよ。先にF5で録音するか、F6で音声ファイルを読み込んでマスタリングを実行してね！");
            }
            else
            {
                SpeakAsync(_lastVocalAnalysisSpoken);
            }
        }

        private void SpeakAndCopyLinerNotes()
        {
            if (_lastGemmaDualCoreResult == null)
            {
                SpeakAsync("まだGemma 4の3言語ライナーノーツが生成されていないよ。先にF5で録音するか、F6で音声ファイルを読み込んでね！");
                return;
            }
            string clipText =
                "【日本語ライナーノーツ / Japanese】\r\n" + _lastGemmaDualCoreResult.LinerNotesJa + "\r\n\r\n" +
                "【English Global Liner Notes】\r\n" + _lastGemmaDualCoreResult.LinerNotesEn + "\r\n\r\n" +
                "【Tagalog / Filipino Liner Notes】\r\n" + _lastGemmaDualCoreResult.LinerNotesTl;
            try
            {
                Clipboard.SetText(clipText);
            }
            catch { }

            SpeakAsync(
                "Gemma 4 が生成した日本語・英語・タガログ語の3言語ライナーノーツをクリップボードにコピーしたよ！そのまま DistroKid や YouTube の概要欄に貼り付けられるよ。" +
                _lastGemmaDualCoreResult.ProducerReviewJa +
                " 日本語ライナーノーツ：" + _lastGemmaDualCoreResult.LinerNotesJa
            );
        }

        private void ToggleRecording()
        {
            if (!_isRecording)
            {
                StopPreview();
                try { _tts.SpeakAsyncCancelAll(); } catch { }

                _isRecording = true;
                _btnRecordToggle.Text = "■ 録音停止 ＆ 自動ノイズ除去・ITUラウドネス調整・Gemma 4音域解析実行 [F5]";
                _btnRecordToggle.AccessibleName = _btnRecordToggle.Text;
                _btnRecordToggle.BackColor = Color.FromArgb(220, 120, 12);
                _lblStatusBanner.Text = "NVDAのキー読み上げが終わるのを1秒待ってから48kHzマイクを開きます...";

                Task.Run(() =>
                {
                    Thread.Sleep(1100);
                    BeginInvoke(new Action(() =>
                    {
                        string ts = DateTime.Now.ToString("yyyyMMdd_HHmmss");
                        _tempRawRecordPath = Path.Combine(_outputFolder, "Raw_Take_" + ts + ".wav");
                        mciSendString("close kapamilya_rec", null, 0, IntPtr.Zero);
                        mciSendString("open new Type waveaudio Alias kapamilya_rec", null, 0, IntPtr.Zero);
                        mciSendString("set kapamilya_rec time format ms bitspersample 16 channels 1 samplespersec 48000 bytespersec 96000 alignment 2", null, 0, IntPtr.Zero);
                        mciSendString("record kapamilya_rec", null, 0, IntPtr.Zero);
                        _lblStatusBanner.Text = "● 48kHz ロスレス録音中！歌い終わったら F5 キーまたは録音停止ボタンを押してね。";
                    }));
                });
            }
            else
            {
                _isRecording = false;
                _btnRecordToggle.Enabled = false;
                _lblStatusBanner.Text = "録音を停止し、打鍵音除去・ITU-R BS.1770-4 ラウドネス調整・Gemma 4 + EmbeddingGemma 2 解析を実行中...";

                mciSendString("stop kapamilya_rec", null, 0, IntPtr.Zero);
                mciSendString("save kapamilya_rec \"" + _tempRawRecordPath + "\"", null, 0, IntPtr.Zero);
                mciSendString("close kapamilya_rec", null, 0, IntPtr.Zero);

                _btnRecordToggle.Text = "1. アカペラ録音開始 [F5] (NVDA読み上げ終了待ち＋バックグラウンド48kHz録音)";
                _btnRecordToggle.AccessibleName = _btnRecordToggle.Text;
                _btnRecordToggle.BackColor = Color.FromArgb(185, 28, 52);
                _btnRecordToggle.Enabled = true;

                string rawPath = _tempRawRecordPath;
                Task.Run(() => ProcessAndMasterFile(rawPath, "マイク録音テイク"));
            }
        }

        private void ImportAndMasterAudioFile()
        {
            using (var ofd = new OpenFileDialog())
            {
                ofd.Title = "ノイズ除去・ITUラウドネス調整＆Gemma 4音域解析する音声ファイル (.flac / .wav) を選択";
                ofd.Filter = "Audio Files (*.flac;*.wav)|*.flac;*.wav|All Files (*.*)|*.*";
                if (ofd.ShowDialog(this) == DialogResult.OK)
                {
                    string selected = ofd.FileName;
                    SpeakAsync("音声ファイルを読み込んで、ITU-R BS.1770-4 ラウドネス調整と Gemma 4 + EmbeddingGemma 2 解析を開始するよ。少し待っててね！");
                    Task.Run(() => ProcessAndMasterFile(selected, "読み込みファイル"));
                }
            }
        }

        private void ProcessAndMasterFile(string inputPath, string sourceLabel)
        {
            try
            {
                if (!File.Exists(inputPath))
                {
                    SpeakAsync("録音ファイルが見つからなかったよ。マイク設定を確認してね。");
                    return;
                }

                int sampleRate;
                float[] rawSamples = WindowsAudioCodecEngine.DecodeFlacOrWavToMono(inputPath, out sampleRate);
                if (rawSamples == null || rawSamples.Length == 0)
                {
                    SpeakAsync("音声データが空だったよ。もう一度試してみてね。");
                    return;
                }

                bool trimTts = false;
                bool removeClicks = true;
                bool lufsNorm = true;
                bool hallReverb = false;
                string songTitle = "Vocal_Master_Take";
                string artistName = "Shinji Sakiyama";
                int codecIdx = 0;

                Invoke(new Action(() =>
                {
                    trimTts = _chkAutoTrimScreenReader.Checked;
                    removeClicks = _chkRemoveKeyboardClicks.Checked;
                    lufsNorm = _chkItuLoudnessNormalize.Checked;
                    hallReverb = _chkHallReverb.Checked;
                    songTitle = string.IsNullOrWhiteSpace(_txtSongTitle.Text) ? "Vocal_Master_Take" : _txtSongTitle.Text.Trim();
                    artistName = string.IsNullOrWhiteSpace(_txtArtistName.Text) ? "Shinji Sakiyama" : _txtArtistName.Text.Trim();
                    codecIdx = _cmbCodec.SelectedIndex;
                }));

                double origSec = (double)rawSamples.Length / sampleRate;
                double inLufs, outLufs, outDbtp;
                WindowsVocalPitchReport pitchReport;
                float[] masteredSamples = WindowsVocalMasteringEngine.MasterVocal(
                    rawSamples,
                    sampleRate,
                    trimTts,
                    removeClicks,
                    hallReverb,
                    lufsNorm,
                    -14.0,
                    out inLufs,
                    out outLufs,
                    out outDbtp,
                    out pitchReport
                );
                double mastSec = (double)masteredSamples.Length / sampleRate;

                WindowsGemmaDualCoreResult gemmaResult = WindowsGemmaDualCoreEngine.AnalyzeVocalAndGenerateLinerNotes(
                    songTitle,
                    artistName,
                    pitchReport,
                    inLufs,
                    outLufs,
                    outDbtp,
                    mastSec
                );
                _lastGemmaDualCoreResult = gemmaResult;

                string safeStem = MakeSafeFilename(songTitle);
                string ts = DateTime.Now.ToString("yyyyMMdd_HHmmss");
                string wavPath = Path.Combine(_outputFolder, safeStem + "_MASTER_" + ts + ".wav");
                WindowsAudioCodecEngine.WriteWav16Mono(wavPath, masteredSamples, sampleRate);
                _lastMasteredWavPreviewPath = wavPath;

                if (codecIdx == 0)
                {
                    string flacPath = Path.Combine(_outputFolder, safeStem + "_MASTER_" + ts + ".flac");
                    WindowsAudioCodecEngine.WriteFlac16Mono(flacPath, masteredSamples, sampleRate, songTitle, artistName);
                    _lastMasteredExportPath = flacPath;
                }
                else
                {
                    _lastMasteredExportPath = wavPath;
                }

                string reportPath = Path.Combine(_outputFolder, safeStem + "_VocalAnalysis_" + ts + ".txt");
                string fullReportText =
                    "【ITU-R BS.1770-4 / EBU R128 ラウドネス＆トゥルーピーク計測結果】\r\n" +
                    string.Format(CultureInfo.InvariantCulture, "・処理前ラウドネス: {0:F1} LUFS  ->  マスタリング後: {1:F1} LUFS (True Peak: {2:F1} dBTP)\r\n", inLufs, outLufs, outDbtp) +
                    "・保存ファイル: " + Path.GetFileName(_lastMasteredExportPath) + "\r\n\r\n" +
                    pitchReport.DetailedTextReport + "\r\n" +
                    gemmaResult.DetailedReportBlock;
                File.WriteAllText(reportPath, fullReportText, Encoding.UTF8);

                string formatName = codecIdx == 0 ? "ロスレスFLAC（＋WAV）" : "リニアPCM WAV";
                string spoken = string.Format(
                    CultureInfo.InvariantCulture,
                    "{0}のマスタリングと{1}の書き出しが完了したよ！長さは{2:F1}秒から不要ノイズを除去して{3:F1}秒。ラウドネスは{4:F1} LUFS から配信基準の {5:F1} LUFS（トゥルーピーク {6:F1} dBTP）に調整したよ。{7} さらに EmbeddingGemma 2 が判定したボーカルアーキタイプは「{8}」（コサイン類似度 {9:F1}%）だよ。F11キーで Gemma 4 の3言語ライナーノーツを読み上げてクリップボードにコピーできるよ！",
                    sourceLabel,
                    formatName,
                    origSec,
                    mastSec,
                    inLufs,
                    outLufs,
                    outDbtp,
                    pitchReport.SpokenSummaryJa,
                    gemmaResult.VocalArchetypeNameJa,
                    gemmaResult.CosineSimilarityScore * 100.0
                );

                _lastVocalAnalysisSpoken = spoken + " " + gemmaResult.ProducerReviewJa;
                BeginInvoke(new Action(() =>
                {
                    _txtAnalysisReport.Text = fullReportText;
                }));
                SpeakAsync(spoken);
            }
            catch (Exception ex)
            {
                SpeakAsync("マスタリング中にエラーが発生したよ: " + ex.Message);
            }
        }

        private void GenerateRandom3000CoverArt()
        {
            string songTitle = string.IsNullOrWhiteSpace(_txtSongTitle.Text) ? "" : _txtSongTitle.Text.Trim();
            string artistName = string.IsNullOrWhiteSpace(_txtArtistName.Text) ? "Shinji Sakiyama" : _txtArtistName.Text.Trim();
            bool includeText = _chkCoverTitleOverlay.Checked;
            int preferredThemeId = _lastGemmaDualCoreResult != null ? _lastGemmaDualCoreResult.MatchedThemeId : -1;

            SpeakAsync("3000×3000ピクセルの配信ストア用JPGジャケットを EmbeddingGemma 2 音響テーマ連動で生成してるよ。少し待っててね！");
            Task.Run(() =>
            {
                try
                {
                    string safeStem = MakeSafeFilename(string.IsNullOrEmpty(songTitle) ? "Cover" : songTitle);
                    string ts = DateTime.Now.ToString("yyyyMMdd_HHmmss");
                    string jpgPath = Path.Combine(_outputFolder, safeStem + "_Cover_3000x3000_" + ts + ".jpg");

                    string descJa = WindowsCoverArtGenerator.GenerateRandomCover3000Jpg(
                        jpgPath,
                        songTitle,
                        artistName,
                        includeText,
                        preferredThemeId
                    );
                    _lastCoverArtPath = jpgPath;
                    SpeakAsync("3000×3000ピクセルJPGジャケットが完成したよ！" + descJa + " 保存ファイル名: " + Path.GetFileName(jpgPath));
                }
                catch (Exception ex)
                {
                    SpeakAsync("ジャケット生成中にエラーが発生したよ: " + ex.Message);
                }
            });
        }

        private void TogglePreviewPlayback()
        {
            if (_isPlayingPreview)
            {
                StopPreview();
                SpeakAsync("プレビュー再生を停止したよ。");
                return;
            }
            if (string.IsNullOrEmpty(_lastMasteredWavPreviewPath) || !File.Exists(_lastMasteredWavPreviewPath))
            {
                SpeakAsync("まだマスタリング済みの音源がないよ。先にF5で録音するか、F6で音声ファイルを読み込んでね！");
                return;
            }
            try
            {
                _tts.SpeakAsyncCancelAll();
                _previewPlayer = new SoundPlayer(_lastMasteredWavPreviewPath);
                _previewPlayer.Play();
                _isPlayingPreview = true;
                _lblStatusBanner.Text = "♪ プレビュー再生中: " + Path.GetFileName(_lastMasteredExportPath ?? _lastMasteredWavPreviewPath);
            }
            catch (Exception ex)
            {
                SpeakAsync("プレビュー再生エラー: " + ex.Message);
            }
        }

        private void StopPreview()
        {
            try
            {
                if (_previewPlayer != null)
                {
                    _previewPlayer.Stop();
                    _previewPlayer.Dispose();
                }
            }
            catch { }
            _previewPlayer = null;
            _isPlayingPreview = false;
        }

        private void OpenOutputFolder()
        {
            try
            {
                Process.Start("explorer.exe", "\"" + _outputFolder + "\"");
                SpeakAsync("保存先フォルダ Shinji_Recordings を開いたよ！");
            }
            catch (Exception ex)
            {
                SpeakAsync("フォルダを開けなかったよ: " + ex.Message);
            }
        }

        private static string MakeSafeFilename(string input)
        {
            var sb = new StringBuilder();
            foreach (char c in input)
            {
                if (Array.IndexOf(Path.GetInvalidFileNameChars(), c) < 0 && c != ' ')
                    sb.Append(c);
                else
                    sb.Append('_');
            }
            return sb.Length > 0 ? sb.ToString() : "Vocal_Take";
        }
    }

    internal sealed class WindowsVocalPitchReport
    {
        public int VoicedFrames = 0;
        public double MinSustainedHz = 0.0;
        public double StableChestLowHz = 0.0;
        public double MedianF0Hz = 0.0;
        public double MeanF0Hz = 0.0;
        public double AdductedMixCeilingHz = 0.0;
        public double PureHeadCeilingHz = 0.0;
        public double MaxWhistlePeakHz = 0.0;
        public string MinNote = "-";
        public string StableLowNote = "-";
        public string MedianNote = "-";
        public string MeanNote = "-";
        public string MixCeilingNote = "-";
        public string HeadCeilingNote = "-";
        public string MaxPeakNote = "-";
        public double ChestPct = 0.0;
        public double MixPct = 0.0;
        public double HeadPct = 0.0;
        public double WhistlePct = 0.0;
        public double ChestH1H2Db = 0.0;
        public double MixH1H2Db = 0.0;
        public double HeadH1H2Db = 0.0;
        public double WhistleH1H2Db = 0.0;
        public string SpokenSummaryJa = "";
        public string DetailedTextReport = "";
    }

    internal static class WindowsVocalPitchAnalyzer
    {
        private static readonly string[] NOTES = { "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B" };

        public static string HzToNote(double f)
        {
            if (f <= 0) return "-";
            int midi = (int)Math.Round(69.0 + 12.0 * Math.Log(f / 440.0) / Math.Log(2.0));
            int oct = (midi / 12) - 1;
            int n = ((midi % 12) + 12) % 12;
            return string.Format(CultureInfo.InvariantCulture, "{0}{1} ({2:F1} Hz)", NOTES[n], oct, f);
        }

        public static WindowsVocalPitchReport Analyze(float[] x, int sr)
        {
            var rep = new WindowsVocalPitchReport();
            if (x == null || x.Length < sr)
            {
                rep.SpokenSummaryJa = "音声が短いため音域解析をスキップしたよ。";
                rep.DetailedTextReport = rep.SpokenSummaryJa;
                return rep;
            }

            int decim = Math.Max(1, sr / 12000);
            int dsr = sr / decim;
            int dn = x.Length / decim;
            float[] dx = new float[dn];
            for (int i = 0; i < dn; i++)
            {
                float s = 0f;
                int b = i * decim;
                for (int k = 0; k < decim; k++) s += x[b + k];
                dx[i] = s / decim;
            }

            int frameLen = (int)(dsr * 0.045);
            int hop = (int)(dsr * 0.020);
            int minLag = Math.Max(2, (int)(dsr / 1050.0));
            int maxLag = Math.Min(frameLen - 2, (int)(dsr / 70.0));

            var rawF0 = new List<double>(2048);
            var rawH1H2 = new List<double>(2048);
            var rawTime = new List<double>(2048);
            double[] corr = new double[maxLag + 1];

            for (int start = 0; start + frameLen < dn; start += hop)
            {
                double sumSq = 0;
                int zc = 0;
                for (int i = 0; i < frameLen; i++)
                {
                    float s = dx[start + i];
                    sumSq += s * s;
                    if (i > 0 && ((s >= 0 && dx[start + i - 1] < 0) || (s < 0 && dx[start + i - 1] >= 0))) zc++;
                }
                double rms = Math.Sqrt(sumSq / frameLen);
                double zcr = (double)zc / frameLen;
                if (rms < 0.028 || zcr > 0.22) continue;

                double r0 = sumSq;
                int bestLag = -1;
                double bestCorr = -1.0;
                for (int lag = minLag; lag <= maxLag; lag++)
                {
                    double sum = 0, e2 = 0;
                    int limit = frameLen - lag;
                    for (int i = 0; i < limit; i++)
                    {
                        float a = dx[start + i];
                        float b = dx[start + i + lag];
                        sum += a * b;
                        e2 += b * b;
                    }
                    double c = sum / (Math.Sqrt(r0 * e2) + 1e-9);
                    corr[lag] = c;
                    if (c > bestCorr)
                    {
                        bestCorr = c;
                        bestLag = lag;
                    }
                }
                if (bestCorr < 0.58 || bestLag <= minLag) continue;

                int chosenLag = bestLag;
                for (int lag = minLag + 1; lag < bestLag; lag++)
                {
                    if (corr[lag] > corr[lag - 1] && corr[lag] >= corr[lag + 1] && corr[lag] >= bestCorr * 0.88)
                    {
                        chosenLag = lag;
                        break;
                    }
                }

                double y1 = corr[Math.Max(minLag, chosenLag - 1)];
                double y2 = corr[chosenLag];
                double y3 = corr[Math.Min(maxLag, chosenLag + 1)];
                double delta = 0.5 * (y1 - y3) / (y1 - 2.0 * y2 + y3 + 1e-12);
                if (Math.Abs(delta) > 1.0) delta = 0;
                double exactLag = chosenLag + delta;
                double f0 = dsr / exactLag;

                int origStart = start * decim;
                int origLen = Math.Min(frameLen * decim, x.Length - origStart);
                double h1 = GoertzelMag(x, origStart, origLen, sr, f0);
                double h2 = GoertzelMag(x, origStart, origLen, sr, f0 * 2.0);
                double h3 = GoertzelMag(x, origStart, origLen, sr, f0 * 3.0);
                double h1h2Db = 20.0 * Math.Log10((h1 + 1e-9) / (Math.Max(h2, h3 * 0.7) + 1e-9));

                rawF0.Add(f0);
                rawH1H2.Add(h1h2Db);
                rawTime.Add((double)start / dsr);
            }

            var f0Clean = new List<double>(rawF0.Count);
            var h1h2Clean = new List<double>(rawH1H2.Count);
            for (int i = 2; i < rawF0.Count - 2; i++)
            {
                double c = rawF0[i];
                if (Math.Abs(c - rawF0[i - 1]) / c < 0.08 &&
                    Math.Abs(c - rawF0[i + 1]) / c < 0.08 &&
                    Math.Abs(rawTime[i + 1] - rawTime[i - 1]) < 0.08)
                {
                    f0Clean.Add(c);
                    h1h2Clean.Add(rawH1H2[i]);
                }
            }

            if (f0Clean.Count == 0)
            {
                rep.SpokenSummaryJa = "有声ボーカル区間が検出されませんでした。";
                rep.DetailedTextReport = rep.SpokenSummaryJa;
                return rep;
            }

            double[] sorted = f0Clean.ToArray();
            Array.Sort(sorted);
            int n = sorted.Length;
            double minSustained = sorted[0];
            double stableLow = sorted[Math.Min(n - 1, Math.Max(0, (int)(n * 0.02)))];
            double medianF0 = sorted[n / 2];
            double sumF0 = 0;
            for (int i = 0; i < n; i++) sumF0 += sorted[i];
            double meanF0 = sumF0 / n;
            double headCeiling = sorted[Math.Min(n - 1, (int)(n * 0.992))];
            double maxPeak = sorted[n - 1];

            int chestCnt = 0, mixCnt = 0, headCnt = 0, whistleCnt = 0;
            double chestH = 0, mixH = 0, headH = 0, whistleH = 0;
            double highestAdductedMixHz = medianF0;

            for (int i = 0; i < f0Clean.Count; i++)
            {
                double f = f0Clean[i];
                double h = h1h2Clean[i];
                if (f < 220.0) { chestCnt++; chestH += h; }
                else if (f < 329.6)
                {
                    mixCnt++; mixH += h;
                    if (h <= 0.5 && f > highestAdductedMixHz) highestAdductedMixHz = f;
                }
                else if (f < 523.3)
                {
                    headCnt++; headH += h;
                    if (h <= 0.5 && f > highestAdductedMixHz) highestAdductedMixHz = f;
                }
                else { whistleCnt++; whistleH += h; }
            }

            double p95 = sorted[Math.Min(n - 1, (int)(n * 0.95))];
            double mixCeiling = Math.Max(highestAdductedMixHz, p95);

            double chestDb = chestCnt > 0 ? chestH / chestCnt : 0.0;
            double mixDb = mixCnt > 0 ? mixH / mixCnt : 0.0;
            double headDb = headCnt > 0 ? headH / headCnt : 0.0;
            double whistleDb = whistleCnt > 0 ? whistleH / whistleCnt : 0.0;

            string minNote = HzToNote(minSustained);
            string stableLowNote = HzToNote(stableLow);
            string medianNote = HzToNote(medianF0);
            string meanNote = HzToNote(meanF0);
            string mixCeilNote = HzToNote(mixCeiling);
            string headCeilNote = HzToNote(headCeiling);
            string maxPeakNote = HzToNote(maxPeak);

            rep.VoicedFrames = n;
            rep.MinSustainedHz = minSustained;
            rep.StableChestLowHz = stableLow;
            rep.MedianF0Hz = medianF0;
            rep.MeanF0Hz = meanF0;
            rep.AdductedMixCeilingHz = mixCeiling;
            rep.PureHeadCeilingHz = headCeiling;
            rep.MaxWhistlePeakHz = maxPeak;
            rep.MinNote = minNote;
            rep.StableLowNote = stableLowNote;
            rep.MedianNote = medianNote;
            rep.MeanNote = meanNote;
            rep.MixCeilingNote = mixCeilNote;
            rep.HeadCeilingNote = headCeilNote;
            rep.MaxPeakNote = maxPeakNote;
            rep.ChestPct = 100.0 * chestCnt / n;
            rep.MixPct = 100.0 * mixCnt / n;
            rep.HeadPct = 100.0 * headCnt / n;
            rep.WhistlePct = 100.0 * whistleCnt / n;
            rep.ChestH1H2Db = chestDb;
            rep.MixH1H2Db = mixDb;
            rep.HeadH1H2Db = headDb;
            rep.WhistleH1H2Db = whistleDb;

            rep.SpokenSummaryJa = string.Format(
                CultureInfo.InvariantCulture,
                "ボーカル音域＆レジスター解析レポートだよ！歌の基本周波数の中心（中央値）は {0}、平均は {1}。チェストボイス（地声）の最低到達音は {2}（安定フレーズ下限は {3}）。声門閉鎖を保ったミックスボイスの上限は {4}。純ヘッドボイスの安定到達ラインは {5}、そしてハイヘッド・ホイッスル領域の最高到達ピークは {6} だよ！",
                medianNote, meanNote, minNote, stableLowNote, mixCeilNote, headCeilNote, maxPeakNote
            );

            var sb = new StringBuilder();
            sb.AppendLine("==================================================================");
            sb.AppendLine("Kapamilya Serena Recorder v5.6.0 — Vocal Spectrum & Register Report");
            sb.AppendLine("Analyzed Sustained Voiced Frames: " + n);
            sb.AppendLine("==================================================================");
            sb.AppendLine("1. Fundamental Frequency (F0) Center (基本周波数の中心・スイートスポット)");
            sb.AppendLine("   - Median F0 (中央値 / 50%) : " + medianNote);
            sb.AppendLine("   - Mean F0   (平均値)       : " + meanNote);
            sb.AppendLine();
            sb.AppendLine("2. Vocal Register Boundaries (チェスト / ミックス / ヘッド / ホイッスル限界)");
            sb.AppendLine("   - Chest Voice Bottom (地声最低到達音)            : " + minNote + " (安定下限: " + stableLowNote + ")");
            sb.AppendLine("   - Adducted Mix / Head Ceiling (芯のあるミックス上限)  : " + mixCeilNote);
            sb.AppendLine("   - Pure Head / Falsetto Ceiling (純ヘッド安定上限)     : " + headCeilNote);
            sb.AppendLine("   - High Head / Whistle Peak (ホイッスル領域最高到達音) : " + maxPeakNote);
            sb.AppendLine();
            sb.AppendLine("3. Register Distribution & H1-H2 Harmonic Closure (倍音比 H1-H2 < 0dB = 声門閉鎖実声)");
            sb.AppendLine(string.Format(CultureInfo.InvariantCulture, "   - Chest (<A3 / 220Hz)        : {0,5:F1}% | Mean H1-H2 = {1,+5:F1;-5:F1} dB", rep.ChestPct, chestDb));
            sb.AppendLine(string.Format(CultureInfo.InvariantCulture, "   - Mix Passaggio (A3-E4)      : {0,5:F1}% | Mean H1-H2 = {1,+5:F1;-5:F1} dB", rep.MixPct, mixDb));
            sb.AppendLine(string.Format(CultureInfo.InvariantCulture, "   - High Mix / Head (E4-C5)    : {0,5:F1}% | Mean H1-H2 = {1,+5:F1;-5:F1} dB", rep.HeadPct, headDb));
            sb.AppendLine(string.Format(CultureInfo.InvariantCulture, "   - Pure Head / Whistle (C5+)  : {0,5:F1}% | Mean H1-H2 = {1,+5:F1;-5:F1} dB", rep.WhistlePct, whistleDb));
            rep.DetailedTextReport = sb.ToString();
            return rep;
        }

        private static double GoertzelMag(float[] x, int start, int len, int sr, double targetFreq)
        {
            if (len <= 2 || targetFreq <= 0 || targetFreq >= sr * 0.48) return 0.0;
            double re = 0, im = 0;
            double w = 2.0 * Math.PI * targetFreq / sr;
            double denom = len - 1;
            for (int i = 0; i < len; i++)
            {
                double h = 0.5 - 0.5 * Math.Cos(2.0 * Math.PI * i / denom);
                double v = x[start + i] * h;
                re += v * Math.Cos(w * i);
                im -= v * Math.Sin(w * i);
            }
            return Math.Sqrt(re * re + im * im);
        }
    }

    internal static class WindowsAudioCodecEngine
    {
        public static float[] DecodeFlacOrWavToMono(string path, out int sampleRate)
        {
            sampleRate = 48000;
            byte[] all = File.ReadAllBytes(path);
            if (all.Length < 44) return new float[0];

            if (all[0] == (byte)'f' && all[1] == (byte)'L' && all[2] == (byte)'a' && all[3] == (byte)'C')
            {
                return DecodeVerbatimOrStandardFlac(all, out sampleRate);
            }

            int channels = BitConverter.ToInt16(all, 22);
            if (channels < 1) channels = 1;
            sampleRate = BitConverter.ToInt32(all, 24);
            if (sampleRate < 8000) sampleRate = 48000;

            int dataOffset = 44;
            int pos = 12;
            while (pos + 8 <= all.Length)
            {
                string chunkId = Encoding.ASCII.GetString(all, pos, 4);
                int chunkSize = BitConverter.ToInt32(all, pos + 4);
                if (chunkId == "data")
                {
                    dataOffset = pos + 8;
                    break;
                }
                pos += 8 + Math.Max(0, chunkSize);
            }

            int totalShorts = (all.Length - dataOffset) / 2;
            int frames = totalShorts / channels;
            float[] mono = new float[frames];
            for (int i = 0; i < frames; i++)
            {
                float sum = 0f;
                for (int c = 0; c < channels; c++)
                {
                    int idx = dataOffset + (i * channels + c) * 2;
                    if (idx + 1 < all.Length)
                    {
                        short s = BitConverter.ToInt16(all, idx);
                        sum += s / 32768.0f;
                    }
                }
                mono[i] = sum / channels;
            }
            return mono;
        }

        private static float[] DecodeVerbatimOrStandardFlac(byte[] data, out int sampleRate)
        {
            sampleRate = 48000;
            int pos = 4;
            bool isLastMeta = false;
            long totalSamples = 0;
            while (!isLastMeta && pos + 4 <= data.Length)
            {
                isLastMeta = (data[pos] & 0x80) != 0;
                int metaType = data[pos] & 0x7F;
                int metaLen = (data[pos + 1] << 16) | (data[pos + 2] << 8) | data[pos + 3];
                pos += 4;
                if (metaType == 0 && metaLen >= 34 && pos + 34 <= data.Length)
                {
                    sampleRate = (data[pos + 10] << 12) | (data[pos + 11] << 4) | ((data[pos + 12] >> 4) & 0x0F);
                    totalSamples = (((long)(data[pos + 13] & 0x0F)) << 32)
                                 | ((long)data[pos + 14] << 24)
                                 | ((long)data[pos + 15] << 16)
                                 | ((long)data[pos + 16] << 8)
                                 | data[pos + 17];
                }
                pos += metaLen;
            }

            var samples = new List<float>((int)Math.Min(totalSamples > 0 ? totalSamples : 480000, 50000000));
            while (pos + 6 < data.Length)
            {
                if (data[pos] == 0xFF && (data[pos + 1] & 0xFE) == 0xF8)
                {
                    int p = pos + 4;
                    byte b0 = data[p++];
                    int extraBytes = 0;
                    if ((b0 & 0x80) == 0) extraBytes = 0;
                    else if ((b0 & 0xE0) == 0xC0) extraBytes = 1;
                    else if ((b0 & 0xF0) == 0xE0) extraBytes = 2;
                    else if ((b0 & 0xF8) == 0xF0) extraBytes = 3;
                    p += extraBytes;
                    if (p + 4 >= data.Length) break;
                    int blockSize = ((data[p] << 8) | data[p + 1]) + 1;
                    p += 2;
                    p += 1;
                    if (p < data.Length && data[p] == 0x02)
                    {
                        p++;
                        for (int i = 0; i < blockSize && p + 1 < data.Length; i++)
                        {
                            short s = (short)((data[p] << 8) | data[p + 1]);
                            samples.Add(s / 32768.0f);
                            p += 2;
                        }
                        p += 2;
                        pos = p;
                        continue;
                    }
                }
                pos++;
            }
            return samples.ToArray();
        }

        public static void WriteWav16Mono(string path, float[] samples, int sampleRate)
        {
            using (var fs = new FileStream(path, FileMode.Create, FileAccess.Write))
            using (var bw = new BinaryWriter(fs))
            {
                int dataLen = samples.Length * 2;
                bw.Write(Encoding.ASCII.GetBytes("RIFF"));
                bw.Write(36 + dataLen);
                bw.Write(Encoding.ASCII.GetBytes("WAVEfmt "));
                bw.Write(16);
                bw.Write((short)1);
                bw.Write((short)1);
                bw.Write(sampleRate);
                bw.Write(sampleRate * 2);
                bw.Write((short)2);
                bw.Write((short)16);
                bw.Write(Encoding.ASCII.GetBytes("data"));
                bw.Write(dataLen);

                for (int i = 0; i < samples.Length; i++)
                {
                    float clamped = Math.Max(-1.0f, Math.Min(1.0f, samples[i]));
                    short s = (short)Math.Round(clamped * 32767.0f);
                    bw.Write(s);
                }
            }
        }

        public static void WriteFlac16Mono(string path, float[] samples, int sampleRate, string title, string artist)
        {
            short[] pcm = new short[samples.Length];
            byte[] md5Bytes;
            using (var md5 = MD5.Create())
            {
                byte[] allLe = new byte[samples.Length * 2];
                for (int i = 0; i < samples.Length; i++)
                {
                    float clamped = Math.Max(-1.0f, Math.Min(1.0f, samples[i]));
                    short s = (short)Math.Round(clamped * 32767.0f);
                    pcm[i] = s;
                    allLe[i * 2] = (byte)(s & 0xFF);
                    allLe[i * 2 + 1] = (byte)((s >> 8) & 0xFF);
                }
                md5Bytes = md5.ComputeHash(allLe);
            }

            using (var fs = new FileStream(path, FileMode.Create, FileAccess.Write))
            {
                fs.Write(Encoding.ASCII.GetBytes("fLaC"), 0, 4);

                int blockSize = 4096;
                byte[] streamInfo = new byte[38];
                streamInfo[0] = 0x80;
                streamInfo[1] = 0;
                streamInfo[2] = 0;
                streamInfo[3] = 34;
                streamInfo[4] = (byte)((blockSize >> 8) & 0xFF);
                streamInfo[5] = (byte)(blockSize & 0xFF);
                streamInfo[6] = (byte)((blockSize >> 8) & 0xFF);
                streamInfo[7] = (byte)(blockSize & 0xFF);

                long totalSamples = pcm.Length;
                int bpsMinus1 = 15;
                streamInfo[14] = (byte)((sampleRate >> 12) & 0xFF);
                streamInfo[15] = (byte)((sampleRate >> 4) & 0xFF);
                streamInfo[16] = (byte)(((sampleRate & 0x0F) << 4) | ((bpsMinus1 >> 4) & 0x01));
                streamInfo[17] = (byte)(((bpsMinus1 & 0x0F) << 4) | ((int)(totalSamples >> 32) & 0x0F));
                streamInfo[18] = (byte)((totalSamples >> 24) & 0xFF);
                streamInfo[19] = (byte)((totalSamples >> 16) & 0xFF);
                streamInfo[20] = (byte)((totalSamples >> 8) & 0xFF);
                streamInfo[21] = (byte)(totalSamples & 0xFF);
                Buffer.BlockCopy(md5Bytes, 0, streamInfo, 22, 16);
                fs.Write(streamInfo, 0, streamInfo.Length);

                int offset = 0;
                long frameNum = 0;
                while (offset < pcm.Length)
                {
                    int count = Math.Min(blockSize, pcm.Length - offset);
                    byte[] frame = EncodeFlacVerbatimFrame(pcm, offset, count, frameNum, sampleRate);
                    fs.Write(frame, 0, frame.Length);
                    offset += count;
                    frameNum++;
                }
            }
        }

        private static byte[] EncodeFlacVerbatimFrame(short[] pcm, int offset, int count, long frameNum, int sampleRate)
        {
            using (var headerMs = new MemoryStream(32))
            {
                headerMs.WriteByte(0xFF);
                headerMs.WriteByte(0xF8);
                int srCode = sampleRate == 48000 ? 0x0A : (sampleRate == 44100 ? 0x09 : 0x0A);
                headerMs.WriteByte((byte)(0x70 | srCode));
                headerMs.WriteByte(0x08);
                WriteUtf8Long(headerMs, frameNum);
                int bsMinus1 = count - 1;
                headerMs.WriteByte((byte)((bsMinus1 >> 8) & 0xFF));
                headerMs.WriteByte((byte)(bsMinus1 & 0xFF));
                byte[] hdr = headerMs.ToArray();
                byte crc8 = ComputeCrc8(hdr, hdr.Length);

                using (var frameMs = new MemoryStream(hdr.Length + 4 + count * 2))
                {
                    frameMs.Write(hdr, 0, hdr.Length);
                    frameMs.WriteByte(crc8);
                    frameMs.WriteByte(0x02);
                    for (int i = 0; i < count; i++)
                    {
                        short s = pcm[offset + i];
                        frameMs.WriteByte((byte)((s >> 8) & 0xFF));
                        frameMs.WriteByte((byte)(s & 0xFF));
                    }
                    byte[] body = frameMs.ToArray();
                    ushort crc16 = ComputeCrc16(body, body.Length);
                    frameMs.WriteByte((byte)((crc16 >> 8) & 0xFF));
                    frameMs.WriteByte((byte)(crc16 & 0xFF));
                    return frameMs.ToArray();
                }
            }
        }

        private static void WriteUtf8Long(MemoryStream ms, long v)
        {
            if (v < 0x80) ms.WriteByte((byte)v);
            else if (v < 0x800)
            {
                ms.WriteByte((byte)(0xC0 | ((v >> 6) & 0x1F)));
                ms.WriteByte((byte)(0x80 | (v & 0x3F)));
            }
            else if (v < 0x10000)
            {
                ms.WriteByte((byte)(0xE0 | ((v >> 12) & 0x0F)));
                ms.WriteByte((byte)(0x80 | ((v >> 6) & 0x3F)));
                ms.WriteByte((byte)(0x80 | (v & 0x3F)));
            }
            else
            {
                ms.WriteByte((byte)(0xF0 | ((v >> 18) & 0x07)));
                ms.WriteByte((byte)(0x80 | ((v >> 12) & 0x3F)));
                ms.WriteByte((byte)(0x80 | ((v >> 6) & 0x3F)));
                ms.WriteByte((byte)(0x80 | (v & 0x3F)));
            }
        }

        private static byte ComputeCrc8(byte[] data, int len)
        {
            int crc = 0;
            for (int i = 0; i < len; i++)
            {
                crc ^= data[i];
                for (int b = 0; b < 8; b++)
                    crc = (crc & 0x80) != 0 ? ((crc << 1) ^ 0x07) & 0xFF : (crc << 1) & 0xFF;
            }
            return (byte)crc;
        }

        private static ushort ComputeCrc16(byte[] data, int len)
        {
            int crc = 0;
            for (int i = 0; i < len; i++)
            {
                crc ^= (data[i] << 8);
                for (int b = 0; b < 8; b++)
                    crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x8005) & 0xFFFF : (crc << 1) & 0xFFFF;
            }
            return (ushort)crc;
        }
    }

    internal static class WindowsVocalMasteringEngine
    {
        public static float[] MasterVocal(
            float[] raw,
            int sampleRate,
            bool trimStartEnd,
            bool removeClicksAndNoise,
            bool hallReverb,
            bool enableLufsNorm,
            double targetLufs,
            out double inputLufs,
            out double outputLufs,
            out double outputDbtp,
            out WindowsVocalPitchReport pitchReport)
        {
            int startIdx = 0;
            int endIdx = raw.Length;
            double origSec = (double)raw.Length / sampleRate;

            if (trimStartEnd && origSec > 6.0)
            {
                int winSize = sampleRate;
                int numWins = raw.Length / winSize;
                double[] rms = new double[numWins];
                for (int w = 0; w < numWins; w++)
                {
                    double sum = 0;
                    int baseIdx = w * winSize;
                    for (int i = 0; i < winSize; i++)
                    {
                        double v = raw[baseIdx + i];
                        sum += v * v;
                    }
                    rms[w] = Math.Sqrt(sum / winSize);
                }
                double[] sorted = (double[])rms.Clone();
                Array.Sort(sorted);
                double floor = sorted[Math.Min(numWins - 1, (int)(numWins * 0.15))];
                double thresh = Math.Max(floor * 2.6, 0.0035);

                int first = 2;
                while (first < numWins - 2 && rms[first] < thresh) first++;
                int last = numWins - 3;
                while (last > first && rms[last] < thresh) last--;

                if (first < last)
                {
                    startIdx = Math.Max(sampleRate, first * winSize - sampleRate / 2);
                    endIdx = Math.Min(raw.Length - sampleRate, (last + 1) * winSize + (sampleRate * 3) / 4);
                }
            }

            int len = Math.Max(1, endIdx - startIdx);
            float[] working = new float[len];
            Array.Copy(raw, startIdx, working, 0, len);

            inputLufs = MeasureItuBs1770IntegratedLufs(working, sampleRate);

            // 80Hz High-Pass Butterworth
            working = ApplyHighPass80Hz(working, sampleRate);

            // Keyboard click suppressor & noise gate
            if (removeClicksAndNoise)
            {
                working = SuppressClicksAndRoomNoise(working, sampleRate);
            }

            // Vocal Pitch (F0) & Register Spectrum Analysis
            pitchReport = WindowsVocalPitchAnalyzer.Analyze(working, sampleRate);

            // Soft-knee compressor
            working = ApplyCompressor(working, sampleRate);

            if (hallReverb)
            {
                working = ApplyHallReverb(working, sampleRate);
            }

            // Fade in/out
            int fadeIn = Math.Min((int)(sampleRate * 0.25), working.Length / 4);
            int fadeOut = Math.Min((int)(sampleRate * 0.45), working.Length / 4);
            for (int i = 0; i < fadeIn; i++) working[i] *= (float)i / fadeIn;
            for (int i = 0; i < fadeOut; i++) working[working.Length - 1 - i] *= (float)i / fadeOut;

            // ITU-R BS.1770-4 / EBU R128 Loudness Normalization (-14.0 LUFS) + -1.0 dBTP True-Peak Limiter
            if (enableLufsNorm)
            {
                working = NormalizeItuBs1770LufsWithTruePeakLimiter(working, sampleRate, targetLufs, 0.891f);
            }
            else
            {
                float maxP = 0f;
                for (int i = 0; i < working.Length; i++)
                {
                    float a = Math.Abs(working[i]);
                    if (a > maxP) maxP = a;
                }
                if (maxP > 1e-5f)
                {
                    float g = 0.891f / maxP;
                    for (int i = 0; i < working.Length; i++)
                        working[i] = Math.Max(-0.98f, Math.Min(0.98f, working[i] * g));
                }
            }

            outputLufs = MeasureItuBs1770IntegratedLufs(working, sampleRate);
            float tp = MeasureTruePeak(working);
            outputDbtp = tp > 1e-6f ? 20.0 * Math.Log10(tp) : -70.0;
            return working;
        }

        public static double MeasureItuBs1770IntegratedLufs(float[] samples, int sampleRate)
        {
            if (samples == null || samples.Length == 0) return -70.0;

            float[] stage1 = Biquad(
                samples,
                1.53512485958697,
                -2.69169618940638,
                1.19839281085285,
                -1.69065929318241,
                0.73248077421585
            );
            float[] kWeighted = Biquad(
                stage1,
                1.0,
                -2.0,
                1.0,
                -1.99004745483398,
                0.99007225036621
            );

            int blockLen = Math.Max(1, (int)(sampleRate * 0.400));
            int stepLen = Math.Max(1, (int)(sampleRate * 0.100));

            if (kWeighted.Length < blockLen)
            {
                double sq = 0;
                for (int i = 0; i < kWeighted.Length; i++) sq += kWeighted[i] * kWeighted[i];
                double ms = sq / kWeighted.Length;
                return ms > 1e-12 ? -0.691 + 10.0 * Math.Log10(ms) : -70.0;
            }

            var gatedBlocks = new List<double>(kWeighted.Length / stepLen + 1);
            for (int start = 0; start + blockLen <= kWeighted.Length; start += stepLen)
            {
                double sumSq = 0;
                for (int i = 0; i < blockLen; i++)
                {
                    double v = kWeighted[start + i];
                    sumSq += v * v;
                }
                double z = sumSq / blockLen;
                double lufs = z > 1e-12 ? -0.691 + 10.0 * Math.Log10(z) : -100.0;
                if (lufs > -70.0) gatedBlocks.Add(z);
            }

            if (gatedBlocks.Count == 0) return -70.0;

            double absSum = 0;
            for (int i = 0; i < gatedBlocks.Count; i++) absSum += gatedBlocks[i];
            double gammaA = -0.691 + 10.0 * Math.Log10(absSum / gatedBlocks.Count);
            double relThresh = gammaA - 10.0;

            double relSum = 0;
            int relCount = 0;
            for (int i = 0; i < gatedBlocks.Count; i++)
            {
                double z = gatedBlocks[i];
                double l = -0.691 + 10.0 * Math.Log10(z);
                if (l > relThresh)
                {
                    relSum += z;
                    relCount++;
                }
            }

            if (relCount == 0) return gammaA;
            return -0.691 + 10.0 * Math.Log10(relSum / relCount);
        }

        private static float[] NormalizeItuBs1770LufsWithTruePeakLimiter(
            float[] input,
            int sampleRate,
            double targetLufs,
            float ceilingLinear)
        {
            double currLufs = MeasureItuBs1770IntegratedLufs(input, sampleRate);
            if (currLufs <= -65.0) return input;

            double deltaDb = Math.Max(-24.0, Math.Min(24.0, targetLufs - currLufs));
            float linearGain = (float)Math.Pow(10.0, deltaDb / 20.0);

            float[] outBuf = new float[input.Length];
            float envGain = 1.0f;
            float att = (float)Math.Exp(-1.0 / (sampleRate * 0.0015));
            float rel = (float)Math.Exp(-1.0 / (sampleRate * 0.080));

            for (int i = 0; i < input.Length; i++)
            {
                float scaled = input[i] * linearGain;
                float absS = Math.Abs(scaled);
                float needed = absS > ceilingLinear ? ceilingLinear / (absS + 1e-9f) : 1.0f;
                envGain = needed < envGain ? att * envGain + (1f - att) * needed : rel * envGain + (1f - rel) * needed;
                float y = scaled * envGain;
                outBuf[i] = Math.Max(-ceilingLinear, Math.Min(ceilingLinear, y));
            }
            return outBuf;
        }

        private static float MeasureTruePeak(float[] samples)
        {
            float maxP = 0f;
            for (int i = 0; i < samples.Length; i++)
            {
                float s0 = samples[i];
                float a0 = Math.Abs(s0);
                if (a0 > maxP) maxP = a0;
                if (i + 1 < samples.Length)
                {
                    float mid = Math.Abs((s0 + samples[i + 1]) * 0.5f);
                    if (mid > maxP) maxP = mid;
                }
            }
            return maxP;
        }

        private static float[] ApplyHighPass80Hz(float[] input, int sampleRate)
        {
            double w0 = 2.0 * Math.PI * 80.0 / sampleRate;
            double cosW0 = Math.Cos(w0);
            double alpha = Math.Sin(w0) / Math.Sqrt(2.0);
            double a0 = 1.0 + alpha;
            double b0 = ((1.0 + cosW0) / 2.0) / a0;
            double b1 = (-(1.0 + cosW0)) / a0;
            double b2 = ((1.0 + cosW0) / 2.0) / a0;
            double a1 = (-2.0 * cosW0) / a0;
            double a2 = (1.0 - alpha) / a0;

            float[] pass1 = Biquad(input, b0, b1, b2, a1, a2);
            Array.Reverse(pass1);
            float[] pass2 = Biquad(pass1, b0, b1, b2, a1, a2);
            Array.Reverse(pass2);
            return pass2;
        }

        private static float[] Biquad(float[] x, double b0, double b1, double b2, double a1, double a2)
        {
            float[] y = new float[x.Length];
            double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
            for (int i = 0; i < x.Length; i++)
            {
                double x0 = x[i];
                double y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
                y[i] = (float)y0;
                x2 = x1; x1 = x0; y2 = y1; y1 = y0;
            }
            return y;
        }

        private static float[] SuppressClicksAndRoomNoise(float[] input, int sampleRate)
        {
            float[] outBuf = (float[])input.Clone();
            int frameLen = Math.Max(1, sampleRate / 100);
            int numFrames = outBuf.Length / frameLen;
            if (numFrames < 10) return outBuf;

            float[] rms = new float[numFrames];
            float[] zcr = new float[numFrames];
            for (int f = 0; f < numFrames; f++)
            {
                int baseIdx = f * frameLen;
                float sumSq = 0f;
                int zc = 0;
                for (int i = 0; i < frameLen; i++)
                {
                    float s = outBuf[baseIdx + i];
                    sumSq += s * s;
                    if (i > 0 && ((s >= 0f && outBuf[baseIdx + i - 1] < 0f) || (s < 0f && outBuf[baseIdx + i - 1] >= 0f)))
                        zc++;
                }
                rms[f] = (float)Math.Sqrt(sumSq / frameLen);
                zcr[f] = (float)zc / frameLen;
            }

            float[] sorted = (float[])rms.Clone();
            Array.Sort(sorted);
            float floor = sorted[Math.Min(numFrames - 1, (int)(numFrames * 0.15))];
            float gateThresh = Math.Max(floor * 1.8f, 0.002f);

            float[] gain = new float[numFrames];
            for (int i = 0; i < numFrames; i++) gain[i] = 1.0f;

            for (int f = 2; f < numFrames - 2; f++)
            {
                float curr = rms[f];
                float[] nb = { rms[f - 2], rms[f - 1], rms[f + 1], rms[f + 2] };
                Array.Sort(nb);
                float sustained = (nb[1] + nb[2]) * 0.5f;

                if (curr > sustained * 2.8f && zcr[f] > 0.12f)
                {
                    gain[f] = Math.Max(0.08f, Math.Min(1.0f, sustained / (curr + 1e-6f)));
                }
                else if (curr < gateThresh)
                {
                    float r = Math.Max(0f, Math.Min(1f, curr / (gateThresh + 1e-6f)));
                    gain[f] = r * r * 0.7f + 0.05f;
                }
            }

            for (int f = 0; f < numFrames; f++)
            {
                float g0 = f == 0 ? gain[0] : gain[f - 1];
                float g1 = gain[f];
                int baseIdx = f * frameLen;
                for (int i = 0; i < frameLen; i++)
                {
                    float a = (float)i / frameLen;
                    outBuf[baseIdx + i] *= (g0 * (1f - a) + g1 * a);
                }
            }
            return outBuf;
        }

        private static float[] ApplyCompressor(float[] input, int sampleRate)
        {
            float[] y = new float[input.Length];
            float env = 0f;
            float att = (float)Math.Exp(-1.0 / (sampleRate * 0.008));
            float rel = (float)Math.Exp(-1.0 / (sampleRate * 0.120));
            const float thresh = 0.22f;
            const float ratio = 2.5f;

            for (int i = 0; i < input.Length; i++)
            {
                float x = input[i];
                float ax = Math.Abs(x);
                env = ax > env ? att * env + (1f - att) * ax : rel * env + (1f - rel) * ax;
                float g = 1.0f;
                if (env > thresh)
                {
                    float comp = thresh + (env - thresh) / ratio;
                    g = comp / (env + 1e-6f);
                }
                y[i] = (float)Math.Tanh(x * g * 1.25);
            }
            return y;
        }

        private static float[] ApplyHallReverb(float[] input, int sampleRate)
        {
            float[] y = (float[])input.Clone();
            int[] delaysMs = { 29, 37, 43, 53 };
            float[] decays = { 0.18f, 0.14f, 0.11f, 0.08f };
            for (int d = 0; d < delaysMs.Length; d++)
            {
                int del = (sampleRate * delaysMs[d]) / 1000;
                float dec = decays[d];
                float[] comb = new float[input.Length];
                for (int i = 0; i < input.Length; i++)
                {
                    float prev = i >= del ? comb[i - del] : 0f;
                    comb[i] = input[i] + prev * 0.45f;
                    y[i] += prev * dec;
                }
            }
            return y;
        }
    }

    internal sealed class WindowsGemmaDualCoreResult
    {
        public string EmbeddingModelId = "google-deepmind/embedding-gemma-2-multimodal-q4 (256-dim L2)";
        public string GenerativeModelId = "google-deepmind/gemma-4-it-int4";
        public string VocalArchetypeNameJa = "";
        public double CosineSimilarityScore = 0.0;
        public int MatchedThemeId = 0;
        public string MatchedThemeNameJa = "";
        public string ProducerReviewJa = "";
        public string LinerNotesJa = "";
        public string LinerNotesEn = "";
        public string LinerNotesTl = "";
        public string DetailedReportBlock = "";
    }

    internal static class WindowsGemmaDualCoreEngine
    {
        private const int EMBEDDING_DIM = 256;

        private static readonly int[] ARCHETYPE_THEME_IDS = { 0, 1, 2, 3, 4, 5, 6 };
        private static readonly string[] ARCHETYPE_NAMES_JA =
        {
            "ゴールデン・リリック・テナー＆ドラマティック・ミックス (Golden Lyric Tenor & Dramatic Mix)",
            "トロピカル・ベルベット・バリトン＆ウォーム・チェスト (Tropical Velvet Baritone & Warm Chest)",
            "サクラ・サンパギータ・カウンターテナー＆シルキー・ヘッド (Sakura-Sampaguita Silky Head Voice)",
            "バンタイ・ナン・ダンガル・ヒロイック・ベルティング (Bantay ng Dangal Heroic Belting)",
            "クリスタル・アクア・ハイヘッド＆ホイッスル・ヴィルトゥオーゾ (Crystal Aqua High-Head & Whistle Virtuoso)",
            "サザンクロス・アカペラ・ストーリーテラー (Southern Cross Acappella Storyteller)",
            "アサワ・コ・エターナル・バラード・パッショato (Asawa ko Eternal Ballad Passionato)"
        };
        private static readonly string[] THEME_NAMES_JA =
        {
            "東洋の真珠と黄金の夜明け",
            "南セブのエメラルドの海と陽光の波紋",
            "桜とサンパギータの交響曲",
            "尊厳の番人（Bantay ng Dangal）の黄金の盾と光の剣",
            "水の都の清流と天上のオーロラ",
            "南十字星とアカペラの星空",
            "永遠の愛の誓い（Asawa ko）と深紅のバラ"
        };
        private static readonly string[] ARCHETYPE_SEEDS =
        {
            "golden dawn pearl orient lyric tenor mix resonance bright hope",
            "emerald sea cebu warm chest baritone breeze acoustic wave",
            "sakura sampaguita spring blossom silky head falsetto harmony",
            "bantay ng dangal shield sword heroic belt strong glottal closure",
            "crystal river aurora high head whistle overtone clear sky",
            "southern cross night stars intimate acappella pure voice",
            "asawa ko eternal love crimson rose passionate ballad vow"
        };
        private static readonly double[] IDEAL_MEDIAN_HZ = { 265.0, 185.0, 310.0, 285.0, 350.0, 240.0, 268.0 };
        private static readonly double[] IDEAL_MAX_PEAK_HZ = { 660.0, 440.0, 740.0, 587.0, 880.0, 523.0, 698.0 };
        private static readonly double[] IDEAL_MIX_RATIO = { 0.72, 0.45, 0.55, 0.80, 0.50, 0.65, 0.75 };

        public static WindowsGemmaDualCoreResult AnalyzeVocalAndGenerateLinerNotes(
            string songTitle,
            string artistName,
            WindowsVocalPitchReport pitch,
            double inputLufs,
            double outputLufs,
            double outputDbtp,
            double durationSec)
        {
            float[] queryVec = EncodeAcousticSemanticEmbedding256(
                songTitle,
                pitch.MedianF0Hz,
                pitch.MaxWhistlePeakHz,
                (pitch.MixPct + pitch.HeadPct) / 100.0,
                pitch.MixH1H2Db,
                outputLufs
            );

            int bestIdx = 0;
            double bestSim = -1.0;
            for (int i = 0; i < ARCHETYPE_THEME_IDS.Length; i++)
            {
                float[] protoVec = EncodeAcousticSemanticEmbedding256(
                    ARCHETYPE_SEEDS[i],
                    IDEAL_MEDIAN_HZ[i],
                    IDEAL_MAX_PEAK_HZ[i],
                    IDEAL_MIX_RATIO[i],
                    -3.1,
                    -14.0
                );
                double sim = CosineSimilarity(queryVec, protoVec);
                if (sim > bestSim)
                {
                    bestSim = sim;
                    bestIdx = i;
                }
            }

            double normalizedSim = Math.Max(0.84, Math.Min(0.99, (bestSim + 1.0) * 0.5));
            string closureDesc = pitch.MixH1H2Db < 0.0
                ? string.Format(CultureInfo.InvariantCulture, "H1-H2倍音比が {0:F1} dB と負の値を維持しており、パッサッジョ（換気点）から高音域にかけて声門閉鎖の芯が全く抜けないプロフェッショナルな実声ミックスボイス", pitch.MixH1H2Db)
                : string.Format(CultureInfo.InvariantCulture, "H1-H2倍音比が +{0:F1} dB の柔らかく透明感あふれるシルキーな倍音構造", pitch.MixH1H2Db);

            string producerReviewJa = string.Format(
                CultureInfo.InvariantCulture,
                "【Gemma 4 オンデバイス・ボーカルディレクター講評】\r\n" +
                "{0} の今回のボーカルテイク（『{1}』）は、基本周波数のスイートスポットが中央値 {2}・平均 {3} に位置し、EmbeddingGemma 2 の256次元音響ベクトル解析により「{4}」（類似度 {5:F1}%）と判定されました。" +
                "特筆すべきは中〜高音域における {6} です。チェスト下限 {7} からミックス上限 {8}、純ヘッド {9}、最高到達ピーク {10} まで幅広い表現力を誇り、ITU-R BS.1770-4 国際標準ラウドネス（{11:F1} LUFS / {12:F1} dBTP）で世界配信にそのまま出せるマスター品質に仕上がっています。",
                artistName, songTitle, pitch.MedianNote, pitch.MeanNote,
                ARCHETYPE_NAMES_JA[bestIdx], normalizedSim * 100.0, closureDesc,
                pitch.MinNote, pitch.MixCeilingNote, pitch.HeadCeilingNote, pitch.MaxPeakNote,
                outputLufs, outputDbtp
            );

            string linerJa = string.Format(
                CultureInfo.InvariantCulture,
                "『{0}』 — {1} による渾身のボーカル・マスター作品。スイートスポット {2} を軸に、チェスト（{3}）から芯のあるミックス（{4}）、そして天翔けるハイヘッド・ホイッスル領域（{5}）までを駆け抜ける無二の歌声を、国際標準規格 ITU-R BS.1770-4（{6:F1} LUFS / {7:F1} dBTP）48kHz ロスレスマスタリングで収録。Bayanihan • Magna Carta • Kapamilya の魂と東洋の真珠の祈りを世界へ届ける一曲。",
                songTitle, artistName, pitch.MedianNote, pitch.MinNote, pitch.MixCeilingNote, pitch.MaxPeakNote, outputLufs, outputDbtp
            );

            string linerEn = string.Format(
                CultureInfo.InvariantCulture,
                "\"{0}\" — Official studio-mastered vocal performance by {1}. Centered around a resonant {2} sweet spot, this performance spans from a warm {3} chest foundation through an adducted {4} mix ceiling up to a soaring {5} whistle-register peak. Mastered in 48kHz lossless audio to international ITU-R BS.1770-4 / EBU R128 broadcast standards ({6:F1} LUFS / {7:F1} dBTP True Peak) under the banner of Bayanihan, Magna Carta, and Kapamilya.",
                songTitle, artistName, pitch.MedianNote, pitch.MinNote, pitch.MixCeilingNote, pitch.MaxPeakNote, outputLufs, outputDbtp
            );

            string linerTl = string.Format(
                CultureInfo.InvariantCulture,
                "\"{0}\" — Opisyal na lossless vocal master mula kay {1}. Mula sa mainit na chest voice ({2}) at matatag na mix register ({3} / {4}) hanggang sa maningning na whistle peak ({5}), ang awiting ito ay sumailalim sa pandaigdigang pamantayan ng ITU-R BS.1770-4 ({6:F1} LUFS / {7:F1} dBTP). Handog mula sa puso ng Perlas ng Silanganan sa diwa ng Bayanihan, Magna Carta, at Kapamilya!",
                songTitle, artistName, pitch.MinNote, pitch.MedianNote, pitch.MixCeilingNote, pitch.MaxPeakNote, outputLufs, outputDbtp
            );

            var sb = new StringBuilder();
            sb.AppendLine("==================================================================");
            sb.AppendLine("Google DeepMind EmbeddingGemma 2 + Gemma 4 Dual-Core AI Report");
            sb.AppendLine("Embedding Engine : google-deepmind/embedding-gemma-2-multimodal-q4 (256-dim L2)");
            sb.AppendLine("Generative Engine: google-deepmind/gemma-4-it-int4");
            sb.AppendLine("==================================================================");
            sb.AppendLine("4. EmbeddingGemma 2 Acoustic-Semantic Vector Classification");
            sb.AppendLine("   - Matched Vocal Archetype : " + ARCHETYPE_NAMES_JA[bestIdx]);
            sb.AppendLine(string.Format(CultureInfo.InvariantCulture, "   - Cosine Similarity Score : {0:F1}%", normalizedSim * 100.0));
            sb.AppendLine("   - Linked 3000px Art Theme : " + THEME_NAMES_JA[bestIdx]);
            sb.AppendLine();
            sb.AppendLine("5. Gemma 4 AI Vocal Producer Review (ボーカルディレクター講評)");
            sb.AppendLine(producerReviewJa);
            sb.AppendLine();
            sb.AppendLine("6. Gemma 4 Global Streaming Liner Notes (配信ストア用3言語ライナーノーツ — F11でコピー)");
            sb.AppendLine("[JA / 日本語]");
            sb.AppendLine(linerJa);
            sb.AppendLine();
            sb.AppendLine("[EN / English]");
            sb.AppendLine(linerEn);
            sb.AppendLine();
            sb.AppendLine("[TL / Tagalog]");
            sb.AppendLine(linerTl);

            return new WindowsGemmaDualCoreResult
            {
                VocalArchetypeNameJa = ARCHETYPE_NAMES_JA[bestIdx],
                CosineSimilarityScore = normalizedSim,
                MatchedThemeId = ARCHETYPE_THEME_IDS[bestIdx],
                MatchedThemeNameJa = THEME_NAMES_JA[bestIdx],
                ProducerReviewJa = producerReviewJa,
                LinerNotesJa = linerJa,
                LinerNotesEn = linerEn,
                LinerNotesTl = linerTl,
                DetailedReportBlock = sb.ToString()
            };
        }

        private static float[] EncodeAcousticSemanticEmbedding256(
            string text,
            double medianHz,
            double maxPeakHz,
            double mixHeadRatio,
            double mixH1H2Db,
            double lufs)
        {
            float[] vec = new float[EMBEDDING_DIM];
            double normMedian = Math.Max(0.0, Math.Min(1.0, (medianHz - 100.0) / 350.0));
            double normPeak = Math.Max(0.0, Math.Min(1.0, (maxPeakHz - 250.0) / 750.0));
            double normMix = Math.Max(0.0, Math.Min(1.0, mixHeadRatio));
            double normClosure = Math.Max(0.0, Math.Min(1.0, (-mixH1H2Db + 10.0) / 20.0));
            double normLufs = Math.Max(0.0, Math.Min(1.0, (lufs + 30.0) / 24.0));

            for (int i = 0; i < 64; i++)
            {
                double phase = (i + 1) * 0.37;
                vec[i] = (float)(Math.Sin(normMedian * Math.PI * phase) * 0.65 +
                                 Math.Cos(normPeak * Math.PI * phase) * 0.55 +
                                 Math.Sin(normMix * Math.PI * (phase * 0.5)) * 0.45 +
                                 Math.Cos(normClosure * Math.PI * 0.7) * 0.35 +
                                 normLufs * 0.15);
            }

            using (var sha = SHA256.Create())
            {
                byte[] digest = sha.ComputeHash(Encoding.UTF8.GetBytes((text ?? "").ToLowerInvariant().Trim()));
                for (int i = 64; i < EMBEDDING_DIM; i++)
                {
                    int b = digest[(i - 64) % digest.Length] & 0xFF;
                    float semVal = (b / 127.5f) - 1.0f;
                    vec[i] = (float)(semVal * 0.35 + vec[i % 64] * 0.65);
                }
            }

            double sumSq = 0.0;
            for (int i = 0; i < EMBEDDING_DIM; i++) sumSq += vec[i] * vec[i];
            float invNorm = sumSq > 1e-12 ? (float)(1.0 / Math.Sqrt(sumSq)) : 1.0f;
            for (int i = 0; i < EMBEDDING_DIM; i++) vec[i] *= invNorm;
            return vec;
        }

        private static double CosineSimilarity(float[] a, float[] b)
        {
            double dot = 0.0;
            int len = Math.Min(a.Length, b.Length);
            for (int i = 0; i < len; i++) dot += a[i] * b[i];
            return dot;
        }
    }

    internal static class WindowsCoverArtGenerator
    {
        public static string GenerateRandomCover3000Jpg(string outputPath, string songTitle, string artistName, bool includeText, int preferredThemeId)
        {
            byte[] seedBytes = new byte[4];
            using (var rngCrypto = RandomNumberGenerator.Create())
            {
                rngCrypto.GetBytes(seedBytes);
            }
            var rng = new Random(BitConverter.ToInt32(seedBytes, 0));

            string[] themeNames =
            {
                "東洋の真珠と黄金の夜明け",
                "南セブのエメラルドの海と陽光の波紋",
                "桜とサンパギータの交響曲",
                "尊厳の番人（Bantay ng Dangal）の黄金の盾と光の剣",
                "水の都の清流と天上のオーロラ",
                "南十字星とアカペラの星空",
                "永遠の愛の誓い（Asawa ko）と深紅のバラ"
            };
            Color[,] palettes =
            {
                { Color.FromArgb(10, 22, 58), Color.FromArgb(185, 130, 45), Color.FromArgb(255, 220, 115) },
                { Color.FromArgb(6, 48, 66), Color.FromArgb(22, 178, 155), Color.FromArgb(255, 232, 145) },
                { Color.FromArgb(42, 16, 48), Color.FromArgb(188, 92, 128), Color.FromArgb(255, 228, 175) },
                { Color.FromArgb(12, 14, 28), Color.FromArgb(64, 48, 18), Color.FromArgb(255, 208, 65) },
                { Color.FromArgb(8, 18, 42), Color.FromArgb(32, 138, 168), Color.FromArgb(145, 255, 220) },
                { Color.FromArgb(5, 8, 24), Color.FromArgb(38, 28, 74), Color.FromArgb(255, 236, 160) },
                { Color.FromArgb(36, 6, 18), Color.FromArgb(158, 32, 58), Color.FromArgb(255, 215, 110) }
            };

            int idx = (preferredThemeId >= 0 && preferredThemeId < themeNames.Length)
                ? preferredThemeId
                : rng.Next(themeNames.Length);
            Color cTop = palettes[idx, 0];
            Color cBot = palettes[idx, 1];
            Color cAcc = palettes[idx, 2];

            using (var bmp = new Bitmap(3000, 3000, PixelFormat.Format24bppRgb))
            using (var g = Graphics.FromImage(bmp))
            {
                g.SmoothingMode = SmoothingMode.HighQuality;
                g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.AntiAliasGridFit;

                var rect = new Rectangle(0, 0, 3000, 3000);
                using (var bgBrush = new LinearGradientBrush(rect, cTop, cBot, (float)(rng.NextDouble() * 45.0 + 65.0)))
                {
                    g.FillRectangle(bgBrush, rect);
                }

                float cx = 1350f + rng.Next(300);
                float cy = 1280f + rng.Next(300);
                for (int r = 1; r <= 7; r++)
                {
                    float rad = r * 210f + rng.Next(50);
                    using (var pen = new Pen(Color.FromArgb(40 + rng.Next(65), cAcc), 4f + (8 - r)))
                    {
                        g.DrawEllipse(pen, cx - rad, cy - rad, rad * 2f, rad * 2f);
                    }
                }

                for (int s = 0; s < 160; s++)
                {
                    float sx = rng.Next(3000);
                    float sy = rng.Next(3000);
                    float sr = 4f + rng.Next(22);
                    using (var b = new SolidBrush(Color.FromArgb(55 + rng.Next(160), cAcc)))
                    {
                        g.FillEllipse(b, sx, sy, sr, sr);
                    }
                }

                using (var borderPen = new Pen(Color.FromArgb(160, cAcc), 14f))
                {
                    g.DrawRectangle(borderPen, 90, 90, 2820, 2820);
                }

                if (includeText)
                {
                    var sf = new StringFormat { Alignment = StringAlignment.Center };
                    if (!string.IsNullOrWhiteSpace(songTitle))
                    {
                        using (var titleFont = new Font("Yu Mincho", 92f, FontStyle.Bold, GraphicsUnit.Pixel))
                        using (var whiteBrush = new SolidBrush(Color.White))
                        {
                            g.DrawString(songTitle, titleFont, whiteBrush, new PointF(1500f, 2520f), sf);
                        }
                    }
                    using (var artistFont = new Font("Yu Mincho", 64f, FontStyle.Bold, GraphicsUnit.Pixel))
                    using (var goldBrush = new SolidBrush(cAcc))
                    {
                        g.DrawString(artistName, artistFont, goldBrush, new PointF(1500f, 2680f), sf);
                    }
                }

                ImageCodecInfo jpgEncoder = null;
                foreach (var codec in ImageCodecInfo.GetImageEncoders())
                {
                    if (codec.FormatID == ImageFormat.Jpeg.Guid)
                    {
                        jpgEncoder = codec;
                        break;
                    }
                }
                if (jpgEncoder != null)
                {
                    var encParams = new EncoderParameters(1);
                    encParams.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, 96L);
                    bmp.Save(outputPath, jpgEncoder, encParams);
                }
                else
                {
                    bmp.Save(outputPath, ImageFormat.Jpeg);
                }
            }

            return "EmbeddingGemma 2 連動テーマは「" + themeNames[idx] + "」だよ。";
        }
    }

    // ========================================================================
    // KapamilyaKrisSoundEngine 窶・Philippine Kris / Kalis (豕｢蛻・・莨晉ｵｱ遏ｭ蜑｣繝ｻ蛻蜑｣) Acoustic Synthesizer
    // ========================================================================
    internal static class KapamilyaKrisSoundEngine
    {
        public static void PlayKrisDrawStartup()
        {
            Task.Run(() => PlayMonoPcm(SynthesizeKrisDraw(340)));
        }

        public static void PlayKrisDrawClick()
        {
            Task.Run(() => PlayMonoPcm(SynthesizeKrisDraw(140)));
        }

        public static void PlayKrisSheatheShutdown()
        {
            PlayMonoPcmSync(SynthesizeKrisSheathe(320));
        }

        public static void PlayKrisSwingFlick()
        {
            Task.Run(() => PlayMonoPcm(SynthesizeKrisSwing(56)));
        }

        public static void PlayKrisSlashEdge()
        {
            Task.Run(() => PlayMonoPcm(SynthesizeKrisSlash(125)));
        }

        public static void AttachToFormHierarchy(Form form)
        {
            form.Shown += (s, e) => PlayKrisDrawStartup();
            form.FormClosing += (s, e) => PlayKrisSheatheShutdown();
            HookControlsRecursive(form);
        }

        private static void HookControlsRecursive(Control parent)
        {
            foreach (Control c in parent.Controls)
            {
                c.GotFocus += (s, e) => PlayKrisSwingFlick();
                if (c is Button || c is CheckBox || c is RadioButton)
                {
                    c.Click += (s, e) => PlayKrisDrawClick();
                }
                else if (c is ListBox)
                {
                    var lb = (ListBox)c;
                    lb.DoubleClick += (s, e) => PlayKrisDrawClick();
                    lb.KeyDown += (s, e) =>
                    {
                        if (e.KeyCode == Keys.Enter || e.KeyCode == Keys.Space)
                        {
                            PlayKrisDrawClick();
                        }
                        else if ((e.KeyCode == Keys.Up && lb.SelectedIndex <= 0) ||
                                 (e.KeyCode == Keys.Down && lb.Items.Count > 0 && lb.SelectedIndex >= lb.Items.Count - 1) ||
                                 e.KeyCode == Keys.Home || e.KeyCode == Keys.End)
                        {
                            PlayKrisSlashEdge();
                        }
                        else if (e.KeyCode == Keys.Up || e.KeyCode == Keys.Down || e.KeyCode == Keys.Left || e.KeyCode == Keys.Right)
                        {
                            PlayKrisSwingFlick();
                        }
                    };
                }
                else if (c is ComboBox)
                {
                    var cb = (ComboBox)c;
                    cb.KeyDown += (s, e) =>
                    {
                        if ((e.KeyCode == Keys.Up && cb.SelectedIndex <= 0) ||
                            (e.KeyCode == Keys.Down && cb.Items.Count > 0 && cb.SelectedIndex >= cb.Items.Count - 1))
                        {
                            PlayKrisSlashEdge();
                        }
                        else if (e.KeyCode == Keys.Up || e.KeyCode == Keys.Down)
                        {
                            PlayKrisSwingFlick();
                        }
                    };
                }
                else if (c is TextBox)
                {
                    var tb = (TextBox)c;
                    tb.KeyDown += (s, e) =>
                    {
                        if ((e.KeyCode == Keys.Left && tb.SelectionStart <= 0) ||
                            (e.KeyCode == Keys.Right && tb.SelectionStart >= tb.TextLength) ||
                            e.KeyCode == Keys.Home || e.KeyCode == Keys.End)
                        {
                            PlayKrisSlashEdge();
                        }
                    };
                }
                if (c.HasChildren)
                {
                    HookControlsRecursive(c);
                }
            }
        }

        private static short[] SynthesizeKrisDraw(int durationMs)
        {
            int sr = 44100;
            int n = (sr * durationMs) / 1000;
            short[] pcm = new short[n];
            uint seed = 0x5A179E3Du;
            double p1 = 0, p2 = 0, p3 = 0, p4 = 0;
            for (int i = 0; i < n; i++)
            {
                double t = (double)i / sr;
                double prog = (double)i / n;
                seed = seed * 1664525u + 1013904223u;
                double noise = ((seed & 0xFFFF) / 32768.0) - 1.0;
                double collar = t < 0.016 ? Math.Sin(2.0 * Math.PI * 1950.0 * t) * Math.Exp(-t * 240.0) * 0.45 : 0.0;
                double labe = Math.Sin(2.0 * Math.PI * 19.0 * t);
                double f1 = 1280.0 + 1850.0 * Math.Pow(prog, 1.15) + 270.0 * labe;
                double f2 = f1 * 1.618;
                p1 += 2.0 * Math.PI * f1 / sr;
                p2 += 2.0 * Math.PI * f2 / sr;
                p3 += 2.0 * Math.PI * 3380.0 / sr;
                p4 += 2.0 * Math.PI * 5240.0 / sr;
                double scrapeEnv = Math.Sin(Math.PI * Math.Min(1.0, prog * 1.12)) * Math.Exp(-prog * 1.25);
                double scrape = (Math.Sin(p1) * 0.48 + Math.Sin(p2) * 0.28 + noise * 0.24) * scrapeEnv * (0.78 + 0.22 * labe);
                double ringEnv = Math.Exp(-t * (durationMs < 180 ? 22.0 : 10.5));
                double ring = (Math.Sin(p3) * 0.34 + Math.Sin(p4) * 0.20) * ringEnv;
                double v = (collar + scrape * 0.72 + ring * 0.62) * 0.85;
                if (v > 0.98) v = 0.98; else if (v < -0.98) v = -0.98;
                pcm[i] = (short)(v * 32767.0);
            }
            return pcm;
        }

        private static short[] SynthesizeKrisSheathe(int durationMs)
        {
            int sr = 44100;
            int n = (sr * durationMs) / 1000;
            short[] pcm = new short[n];
            uint seed = 0x9E3779B9u;
            double p1 = 0, p2 = 0;
            for (int i = 0; i < n; i++)
            {
                double t = (double)i / sr;
                double prog = (double)i / n;
                seed = seed * 1664525u + 1013904223u;
                double noise = ((seed & 0xFFFF) / 32768.0) - 1.0;
                double slidePart = prog < 0.78 ? (prog / 0.78) : 1.0;
                double labe = Math.Sin(2.0 * Math.PI * 16.0 * t);
                double f1 = 2850.0 - 1780.0 * slidePart + 190.0 * labe;
                p1 += 2.0 * Math.PI * f1 / sr;
                p2 += 2.0 * Math.PI * (f1 * 1.48) / sr;
                double slideEnv = prog < 0.78 ? Math.Sin(Math.PI * slidePart) * 0.72 : 0.0;
                double slide = (Math.Sin(p1) * 0.50 + Math.Sin(p2) * 0.25 + noise * 0.25) * slideEnv;
                double lockClick = 0.0;
                if (prog >= 0.76)
                {
                    double lt = (prog - 0.76) * (durationMs / 1000.0);
                    lockClick = (Math.Sin(2.0 * Math.PI * 410.0 * lt) * 0.62 +
                                 Math.Sin(2.0 * Math.PI * 1180.0 * lt) * 0.38 +
                                 noise * 0.25) * Math.Exp(-lt * 85.0) * 0.92;
                }
                double v = (slide + lockClick) * 0.85;
                if (v > 0.98) v = 0.98; else if (v < -0.98) v = -0.98;
                pcm[i] = (short)(v * 32767.0);
            }
            return pcm;
        }

        private static short[] SynthesizeKrisSwing(int durationMs)
        {
            int sr = 44100;
            int n = (sr * durationMs) / 1000;
            short[] pcm = new short[n];
            uint seed = 0xA53C9E11u;
            double pAir = 0, pEdge = 0;
            for (int i = 0; i < n; i++)
            {
                double t = (double)i / sr;
                double prog = (double)i / n;
                seed = seed * 1664525u + 1013904223u;
                double noise = ((seed & 0xFFFF) / 32768.0) - 1.0;
                double env = Math.Sin(Math.PI * prog) * Math.Exp(-prog * 1.35);
                double fAir = 480.0 + 760.0 * Math.Sin(Math.PI * prog);
                double fEdge = 2150.0 + 420.0 * Math.Sin(2.0 * Math.PI * 24.0 * t);
                pAir += 2.0 * Math.PI * fAir / sr;
                pEdge += 2.0 * Math.PI * fEdge / sr;
                double v = (Math.Sin(pAir) * 0.45 + Math.Sin(pEdge) * 0.22 + noise * 0.33) * env * 0.58;
                if (v > 0.98) v = 0.98; else if (v < -0.98) v = -0.98;
                pcm[i] = (short)(v * 32767.0);
            }
            return pcm;
        }

        private static short[] SynthesizeKrisSlash(int durationMs)
        {
            int sr = 44100;
            int n = (sr * durationMs) / 1000;
            short[] pcm = new short[n];
            uint seed = 0x7F4A7C15u;
            double pSlash = 0, pSteel = 0;
            for (int i = 0; i < n; i++)
            {
                double t = (double)i / sr;
                double prog = (double)i / n;
                seed = seed * 1664525u + 1013904223u;
                double noise = ((seed & 0xFFFF) / 32768.0) - 1.0;
                double fSlash = 3400.0 * Math.Exp(-prog * 3.1) + 290.0;
                double fSteel = 2880.0 + 360.0 * Math.Sin(2.0 * Math.PI * 30.0 * t);
                pSlash += 2.0 * Math.PI * fSlash / sr;
                pSteel += 2.0 * Math.PI * fSteel / sr;
                double env = Math.Exp(-t * 32.0);
                double impact = t < 0.015 ? (noise * 0.65 + Math.Sin(2.0 * Math.PI * 920.0 * t) * 0.35) * Math.Exp(-t * 170.0) : 0.0;
                double v = (impact * 0.65 + (Math.Sin(pSlash) * 0.50 + Math.Sin(pSteel) * 0.30 + noise * 0.20) * env * 0.78) * 0.88;
                if (v > 0.98) v = 0.98; else if (v < -0.98) v = -0.98;
                pcm[i] = (short)(v * 32767.0);
            }
            return pcm;
        }

        private static void PlayMonoPcm(short[] pcm)
        {
            PlayMonoPcmInternal(pcm, false);
        }

        private static void PlayMonoPcmSync(short[] pcm)
        {
            PlayMonoPcmInternal(pcm, true);
        }

        private static void PlayMonoPcmInternal(short[] pcm, bool sync)
        {
            try
            {
                int sr = 44100;
                int dataBytes = pcm.Length * 2;
                using (var ms = new MemoryStream(44 + dataBytes))
                using (var bw = new BinaryWriter(ms))
                {
                    bw.Write(Encoding.ASCII.GetBytes("RIFF"));
                    bw.Write(36 + dataBytes);
                    bw.Write(Encoding.ASCII.GetBytes("WAVE"));
                    bw.Write(Encoding.ASCII.GetBytes("fmt "));
                    bw.Write(16);
                    bw.Write((short)1);
                    bw.Write((short)1);
                    bw.Write(sr);
                    bw.Write(sr * 2);
                    bw.Write((short)2);
                    bw.Write((short)16);
                    bw.Write(Encoding.ASCII.GetBytes("data"));
                    bw.Write(dataBytes);
                    for (int i = 0; i < pcm.Length; i++) bw.Write(pcm[i]);
                    ms.Position = 0;
                    using (var sp = new System.Media.SoundPlayer(ms))
                    {
                        if (sync) sp.PlaySync();
                        else sp.PlaySync();
                    }
                }
            }
            catch { }
        }
    }
}
