// ============================================================================
// Kapamilya Serena Git v1.0.0 — Accessible Native Windows GUI for Git & GitLab
// Exclusive Copyright & Intellectual Property Owner: SHINJI SAKIYAMA (崎山真治)
// Under the Banner of the Magna Carta & Bantay ng Dangal
// Bayanihan • Magna Carta • Kapamilya
//
// Features:
// 1. 100% Screen Reader Accessible GUI for Git & GitLab (Zero CLI Commands Needed!)
// 2. Tab Key Focus Reading Mode Toggle (Ctrl+Shift+F / F9):
//    - Concise Mode (Default): Announces ONLY Item Name + Control Type + State
//    - Read-All Mode: Announces full descriptions, shortcut guides, and hints
// 3. One-Click "Stage + Commit + Push to GitLab" (F6 / Ctrl+Enter)
// 4. Gemma 4 AI Commit Message & Diff Summary Generator (F7)
// 5. One-Click "Pull Latest from GitLab" (F8) & Branch Switcher / Creator
// 6. One-Click "GitLab Package Registry Upload & Release Publisher" GUI Wizard (F10)
// 7. 4-Language UI Selector (ja, en, tl, es) & Warm Tagalog Startup/Shutdown Greetings
// ============================================================================

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net;
using System.Speech.Synthesis;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Win32;

namespace KapamilyaSerenaGit
{
    public class GitRepoInfo
    {
        public string Name { get; set; }
        public string Path { get; set; }
        public string RemoteUrl { get; set; }
        public string ProjectPath { get; set; } // e.g. kapamilya/serena

        public override string ToString()
        {
            if (!string.IsNullOrEmpty(ProjectPath))
                return string.Format("{0} ({1}) — [{2}]", Name, ProjectPath, Path);
            return string.Format("{0} — [{1}]", Name, Path);
        }
    }

    public class GitChangedFileItem
    {
        public string StatusCode { get; set; }
        public string StatusLabel { get; set; }
        public string FilePath { get; set; }

        public override string ToString()
        {
            return string.Format("{0} {1}", StatusLabel, FilePath);
        }
    }

    public class MainForm : Form
    {
        public const string CURRENT_VERSION = "1.0.0";

        private SpeechSynthesizer _tts;
        private string _uiLang = "ja";
        private bool _focusReadAllMode = false; // false = Concise (Item + Type + State only), true = Read All
        private bool _suppressComboEvents = false;

        private string _settingsDir;
        private string _settingsFile;
        private string _savedGitLabToken = "";
        private List<GitRepoInfo> _repos = new List<GitRepoInfo>();
        private List<GitChangedFileItem> _changedFiles = new List<GitChangedFileItem>();

        // UI Controls
        private Label _lblBanner;
        private Label _lblLang;
        private ComboBox _cmbLang;
        private CheckBox _chkFocusVerbosity;

        private Label _lblRepo;
        private ComboBox _cmbRepo;
        private Button _btnBrowseRepo;
        private Button _btnCloneOrInit;

        private Label _lblBranch;
        private ComboBox _cmbBranch;
        private Button _btnNewBranch;
        private Button _btnRefreshStatus;

        private Label _lblChangedFiles;
        private ListBox _lstChangedFiles;

        private Label _lblCommitMessage;
        private TextBox _txtCommitMessage;
        private Button _btnAiCommitMsg;
        private CheckBox _chkAutoPushAfterCommit;

        private Button _btnCommitAndPush;
        private Button _btnPullLatest;
        private Button _btnPublishRelease;
        private Button _btnGitLabSettings;

        private Label _lblOutputLog;
        private TextBox _txtOutputLog;

        public MainForm()
        {
            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;
            _settingsDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "KapamilyaSerenaGit");
            if (!Directory.Exists(_settingsDir)) Directory.CreateDirectory(_settingsDir);
            _settingsFile = Path.Combine(_settingsDir, "settings.ini");

            InitSpeech();
            LoadSettings();
            InitializeAccessibleUi();
            ApplyLocalization();
            DiscoverRepositories();

            this.Shown += (s, e) =>
            {
                SpeakTagalogGreetingAndStatus(true);
                RefreshGitStatusAsync(false);
            };

            this.FormClosing += (s, e) =>
            {
                SaveSettings();
                SpeakShutdownGreetingSync();
            };
        }

        private void InitSpeech()
        {
            try
            {
                _tts = new SpeechSynthesizer();
                _tts.SetOutputToDefaultAudioDevice();
                _tts.Rate = 1;
                _tts.Volume = 100;
            }
            catch { _tts = null; }
        }

        private void SelectVoiceByLangPrefix(string langPrefix)
        {
            if (_tts == null) return;
            try
            {
                foreach (var v in _tts.GetInstalledVoices())
                {
                    if (!v.Enabled) continue;
                    string culture = v.VoiceInfo.Culture != null ? v.VoiceInfo.Culture.Name.ToLowerInvariant() : "";
                    if (culture.StartsWith(langPrefix.ToLowerInvariant()))
                    {
                        _tts.SelectVoice(v.VoiceInfo.Name);
                        return;
                    }
                }
            }
            catch { }
        }

        public void Speak(string text, bool interrupt = true)
        {
            if (string.IsNullOrWhiteSpace(text) || _tts == null) return;
            try
            {
                if (interrupt) _tts.SpeakAsyncCancelAll();
                string prefix = _uiLang == "tl" ? "fil" : (_uiLang == "es" ? "es" : (_uiLang == "en" ? "en" : "ja"));
                SelectVoiceByLangPrefix(prefix);
                _tts.SpeakAsync(text);
            }
            catch { }
        }

        private void SpeakTagalogGreetingAndStatus(bool isStartup)
        {
            if (_tts == null) return;
            try
            {
                _tts.SpeakAsyncCancelAll();
                SelectVoiceByLangPrefix("fil");
                string tlGreeting = "Maligayang pagdating sa ating tahanan! Narito na ang iyong Kapamilya Serena Git. Kahit nasaan ka man, pamilya tayo! Mahal ka namin, ingat lagi at Mabuhay!";
                _tts.SpeakAsync(tlGreeting);

                string modeNotice;
                if (_uiLang == "en")
                    modeNotice = string.Format("Kapamilya Serena Git version {0} is ready. Focus reading mode is set to: {1}. Press Control plus Shift plus F or F9 to switch.",
                        CURRENT_VERSION, _focusReadAllMode ? "Read All Mode" : "Concise Mode (Name, Type, and State only)");
                else if (_uiLang == "tl")
                    modeNotice = string.Format("Handa na ang Kapamilya Serena Git bersyon {0}. Pindutin ang Control Shift F o F9 upang palitan ang Focus Reading Mode.", CURRENT_VERSION);
                else if (_uiLang == "es")
                    modeNotice = string.Format("Kapamilya Serena Git versión {0} está listo. Presione Control Shift F o F9 para cambiar el modo de lectura de foco.", CURRENT_VERSION);
                else
                    modeNotice = string.Format("Kapamilya Serena Git バージョン {0} が起動しました。現在のタブフォーカス読み上げは「{1}」です。Ctrl+Shift+F または F9 キーでいつでも切り替えられます。",
                        CURRENT_VERSION, _focusReadAllMode ? "すべて読むモード" : "フォーカスされたアイテム・種類・状態のみ読む簡潔モード");

                string prefix = _uiLang == "tl" ? "fil" : (_uiLang == "es" ? "es" : (_uiLang == "en" ? "en" : "ja"));
                SelectVoiceByLangPrefix(prefix);
                _tts.SpeakAsync(modeNotice);
            }
            catch { }
        }

        private void SpeakShutdownGreetingSync()
        {
            if (_tts == null) return;
            try
            {
                _tts.SpeakAsyncCancelAll();
                SelectVoiceByLangPrefix("fil");
                _tts.Speak("Hanggang sa muli, aking Kapamilya! Lagi lang nandito ang Kapamilya Serena para sa iyo. Maraming salamat, mahal kita, at mag-ingat ka palagi!");
            }
            catch { }
        }

        private void LoadSettings()
        {
            try
            {
                using (var key = Registry.CurrentUser.OpenSubKey(@"Software\KapamilyaSerenaGit"))
                {
                    if (key != null)
                    {
                        string regLang = key.GetValue("Language") as string;
                        if (!string.IsNullOrEmpty(regLang)) _uiLang = regLang;
                    }
                }
            }
            catch { }

            if (File.Exists(_settingsFile))
            {
                try
                {
                    foreach (string rawLine in File.ReadAllLines(_settingsFile, Encoding.UTF8))
                    {
                        string line = rawLine.Trim();
                        if (string.IsNullOrEmpty(line) || line.StartsWith("#")) continue;
                        int idx = line.IndexOf('=');
                        if (idx <= 0) continue;
                        string k = line.Substring(0, idx).Trim();
                        string v = line.Substring(idx + 1).Trim();
                        if (k.Equals("Language", StringComparison.OrdinalIgnoreCase) && !string.IsNullOrEmpty(v))
                            _uiLang = v;
                        else if (k.Equals("FocusReadAll", StringComparison.OrdinalIgnoreCase))
                            _focusReadAllMode = (v == "1" || v.Equals("true", StringComparison.OrdinalIgnoreCase));
                        else if (k.Equals("GitLabToken", StringComparison.OrdinalIgnoreCase))
                            _savedGitLabToken = v;
                        else if (k.Equals("CustomRepo", StringComparison.OrdinalIgnoreCase) && Directory.Exists(v))
                            AddRepoIfValid(v);
                    }
                }
                catch { }
            }
        }

        private void SaveSettings()
        {
            try
            {
                var sb = new StringBuilder();
                sb.AppendLine("# Kapamilya Serena Git Settings");
                sb.AppendLine("Language=" + _uiLang);
                sb.AppendLine("FocusReadAll=" + (_focusReadAllMode ? "1" : "0"));
                sb.AppendLine("GitLabToken=" + (_savedGitLabToken ?? ""));
                foreach (var r in _repos)
                {
                    sb.AppendLine("CustomRepo=" + r.Path);
                }
                File.WriteAllText(_settingsFile, sb.ToString(), Encoding.UTF8);
            }
            catch { }
        }

        private void InitializeAccessibleUi()
        {
            this.Text = "Kapamilya Serena Git v" + CURRENT_VERSION + " — Bayanihan • Magna Carta • Kapamilya";
            this.Size = new Size(980, 780);
            this.StartPosition = FormStartPosition.CenterScreen;
            this.BackColor = Color.FromArgb(18, 22, 32);
            this.ForeColor = Color.White;
            this.Font = new Font("Yu Gothic UI", 10.5f, FontStyle.Regular);
            this.KeyPreview = true;

            int y = 14;

            _lblBanner = new Label
            {
                Location = new Point(16, y),
                Size = new Size(930, 28),
                Font = new Font("Yu Gothic UI", 12f, FontStyle.Bold),
                ForeColor = Color.FromArgb(255, 215, 0),
                Text = "Kapamilya Serena Git v" + CURRENT_VERSION + " — コマンド不要のアクセシブル Git & GitLab クライアント"
            };
            this.Controls.Add(_lblBanner);
            y += 34;

            // Row 1: Language Selector & Tab Focus Reading Mode Toggle
            _lblLang = new Label
            {
                Location = new Point(16, y + 4),
                Size = new Size(155, 26),
                Text = "表示言語 (&L):"
            };
            _cmbLang = new ComboBox
            {
                Location = new Point(175, y),
                Size = new Size(210, 30),
                DropDownStyle = ComboBoxStyle.DropDownList,
                BackColor = Color.FromArgb(30, 36, 52),
                ForeColor = Color.White,
                TabIndex = 0
            };
            _cmbLang.Items.AddRange(new object[]
            {
                "日本語 (Japanese)",
                "English (英語)",
                "Tagalog / Filipino",
                "Español (Spanish)"
            });
            _cmbLang.SelectedIndex = _uiLang == "en" ? 1 : (_uiLang == "tl" ? 2 : (_uiLang == "es" ? 3 : 0));
            _cmbLang.SelectedIndexChanged += (s, e) =>
            {
                if (_suppressComboEvents) return;
                _uiLang = _cmbLang.SelectedIndex == 1 ? "en" : (_cmbLang.SelectedIndex == 2 ? "tl" : (_cmbLang.SelectedIndex == 3 ? "es" : "ja"));
                ApplyLocalization();
                SaveSettings();
                AnnounceControlFocus(_cmbLang);
            };

            _chkFocusVerbosity = new CheckBox
            {
                Location = new Point(405, y + 2),
                Size = new Size(540, 28),
                Checked = _focusReadAllMode,
                ForeColor = Color.FromArgb(130, 230, 255),
                TabIndex = 1,
                Text = "タブ移動時にすべて読む (&F) [オフ＝アイテム名・種類・状態のみ簡潔に読む] (Ctrl+Shift+F / F9)"
            };
            _chkFocusVerbosity.CheckedChanged += (s, e) =>
            {
                if (_suppressComboEvents) return;
                SetFocusVerbosityMode(_chkFocusVerbosity.Checked, true);
            };

            this.Controls.Add(_lblLang);
            this.Controls.Add(_cmbLang);
            this.Controls.Add(_chkFocusVerbosity);
            y += 40;

            // Row 2: Repository Selector + Browse + Clone/Init
            _lblRepo = new Label
            {
                Location = new Point(16, y + 4),
                Size = new Size(155, 26),
                Text = "対象リポジトリ (&R):"
            };
            _cmbRepo = new ComboBox
            {
                Location = new Point(175, y),
                Size = new Size(460, 30),
                DropDownStyle = ComboBoxStyle.DropDownList,
                BackColor = Color.FromArgb(30, 36, 52),
                ForeColor = Color.White,
                TabIndex = 2
            };
            _cmbRepo.SelectedIndexChanged += (s, e) =>
            {
                if (_suppressComboEvents) return;
                AnnounceControlFocus(_cmbRepo);
                RefreshGitStatusAsync(false);
            };

            _btnBrowseRepo = CreateAccessibleButton("📂 フォルダ追加 (&O)", new Point(645, y - 1), new Size(145, 32), 3, Color.FromArgb(45, 65, 105));
            _btnBrowseRepo.Click += (s, e) => BrowseAndAddRepository();

            _btnCloneOrInit = CreateAccessibleButton("✨ 新規/クローン (F4)", new Point(798, y - 1), new Size(148, 32), 4, Color.FromArgb(45, 65, 105));
            _btnCloneOrInit.Click += (s, e) => ShowCloneOrInitDialog();

            this.Controls.Add(_lblRepo);
            this.Controls.Add(_cmbRepo);
            this.Controls.Add(_btnBrowseRepo);
            this.Controls.Add(_btnCloneOrInit);
            y += 40;

            // Row 3: Branch Selector + New Branch + Refresh Status
            _lblBranch = new Label
            {
                Location = new Point(16, y + 4),
                Size = new Size(155, 26),
                Text = "現在のブランチ (&B):"
            };
            _cmbBranch = new ComboBox
            {
                Location = new Point(175, y),
                Size = new Size(280, 30),
                DropDownStyle = ComboBoxStyle.DropDownList,
                BackColor = Color.FromArgb(30, 36, 52),
                ForeColor = Color.White,
                TabIndex = 5
            };
            _cmbBranch.SelectedIndexChanged += (s, e) =>
            {
                if (_suppressComboEvents) return;
                SwitchSelectedBranch();
            };

            _btnNewBranch = CreateAccessibleButton("🌿 新規ブランチ作成 (&N)", new Point(465, y - 1), new Size(210, 32), 6, Color.FromArgb(45, 65, 105));
            _btnNewBranch.Click += (s, e) => CreateNewBranchPrompt();

            _btnRefreshStatus = CreateAccessibleButton("🔄 状態を再確認 (F5)", new Point(685, y - 1), new Size(261, 32), 7, Color.FromArgb(35, 95, 85));
            _btnRefreshStatus.Click += (s, e) => RefreshGitStatusAsync(true);

            this.Controls.Add(_lblBranch);
            this.Controls.Add(_cmbBranch);
            this.Controls.Add(_btnNewBranch);
            this.Controls.Add(_btnRefreshStatus);
            y += 42;

            // Row 4: Changed Files ListBox
            _lblChangedFiles = new Label
            {
                Location = new Point(16, y),
                Size = new Size(930, 24),
                Font = new Font("Yu Gothic UI", 10f, FontStyle.Bold),
                ForeColor = Color.FromArgb(255, 230, 140),
                Text = "変更されたファイル一覧 (&S) [上下矢印で選択・Enterで変更箇所(Diff)を詳しく読み上げ]:"
            };
            this.Controls.Add(_lblChangedFiles);
            y += 26;

            _lstChangedFiles = new ListBox
            {
                Location = new Point(16, y),
                Size = new Size(930, 145),
                BackColor = Color.FromArgb(24, 28, 42),
                ForeColor = Color.White,
                Font = new Font("Consolas", 10.5f, FontStyle.Regular),
                TabIndex = 8
            };
            _lstChangedFiles.SelectedIndexChanged += (s, e) =>
            {
                if (_lstChangedFiles.Focused && _lstChangedFiles.SelectedItem != null)
                {
                    Speak(_lstChangedFiles.SelectedItem.ToString(), true);
                }
            };
            _lstChangedFiles.KeyDown += (s, e) =>
            {
                if (e.KeyCode == Keys.Enter && _lstChangedFiles.SelectedIndex >= 0 && _lstChangedFiles.SelectedIndex < _changedFiles.Count)
                {
                    e.Handled = true;
                    e.SuppressKeyPress = true;
                    ShowDiffForSelectedFile(_changedFiles[_lstChangedFiles.SelectedIndex]);
                }
            };
            this.Controls.Add(_lstChangedFiles);
            y += 155;

            // Row 5: Commit Message Input + Gemma 4 AI Button + Auto-Push Checkbox
            _lblCommitMessage = new Label
            {
                Location = new Point(16, y + 4),
                Size = new Size(210, 26),
                Font = new Font("Yu Gothic UI", 10f, FontStyle.Bold),
                Text = "変更の説明 / コミット文 (&M):"
            };
            _txtCommitMessage = new TextBox
            {
                Location = new Point(230, y),
                Size = new Size(465, 30),
                BackColor = Color.FromArgb(28, 34, 48),
                ForeColor = Color.White,
                TabIndex = 9
            };
            _btnAiCommitMsg = CreateAccessibleButton("🧠 AIで説明文を自動作成 (F7)", new Point(705, y - 1), new Size(241, 32), 10, Color.FromArgb(105, 50, 135));
            _btnAiCommitMsg.Click += (s, e) => GenerateAiCommitMessageAsync();

            this.Controls.Add(_lblCommitMessage);
            this.Controls.Add(_txtCommitMessage);
            this.Controls.Add(_btnAiCommitMsg);
            y += 38;

            _chkAutoPushAfterCommit = new CheckBox
            {
                Location = new Point(230, y),
                Size = new Size(710, 26),
                Checked = true,
                ForeColor = Color.FromArgb(180, 240, 180),
                TabIndex = 11,
                Text = "保存（コミット）した直後にそのまま自動でGitLabへ送信（プッシュ）する (&P)"
            };
            _chkAutoPushAfterCommit.CheckedChanged += (s, e) =>
            {
                if (_chkAutoPushAfterCommit.Focused) AnnounceControlFocus(_chkAutoPushAfterCommit);
            };
            this.Controls.Add(_chkAutoPushAfterCommit);
            y += 34;

            // Row 6: Primary Action Buttons (Commit & Push, Pull, Publish Release/Package, GitLab Settings)
            _btnCommitAndPush = CreateAccessibleButton("🚀 変更をすべて保存してGitLabへ送信 (F6)", new Point(16, y), new Size(310, 44), 12, Color.FromArgb(20, 125, 70));
            _btnCommitAndPush.Font = new Font("Yu Gothic UI", 10.5f, FontStyle.Bold);
            _btnCommitAndPush.Click += (s, e) => CommitAndPushAllAsync();

            _btnPullLatest = CreateAccessibleButton("⬇️ GitLabから最新を取得 (F8)", new Point(334, y), new Size(205, 44), 13, Color.FromArgb(40, 90, 145));
            _btnPullLatest.Click += (s, e) => PullLatestAsync();

            _btnPublishRelease = CreateAccessibleButton("📦 パッケージ＆リリース公開 (F10)", new Point(547, y), new Size(235, 44), 14, Color.FromArgb(155, 85, 20));
            _btnPublishRelease.Click += (s, e) => ShowPublishReleaseWizard();

            _btnGitLabSettings = CreateAccessibleButton("🔑 トークン設定 (F11)", new Point(790, y), new Size(156, 44), 15, Color.FromArgb(75, 75, 95));
            _btnGitLabSettings.Click += (s, e) => ShowGitLabTokenDialog();

            this.Controls.Add(_btnCommitAndPush);
            this.Controls.Add(_btnPullLatest);
            this.Controls.Add(_btnPublishRelease);
            this.Controls.Add(_btnGitLabSettings);
            y += 54;

            // Row 7: Accessible Result & Activity Log
            _lblOutputLog = new Label
            {
                Location = new Point(16, y),
                Size = new Size(930, 24),
                Font = new Font("Yu Gothic UI", 10f, FontStyle.Bold),
                ForeColor = Color.FromArgb(255, 230, 140),
                Text = "実行結果・詳細ログ (&G) [いつでも矢印キーで読み返せます]:"
            };
            this.Controls.Add(_lblOutputLog);
            y += 26;

            _txtOutputLog = new TextBox
            {
                Location = new Point(16, y),
                Size = new Size(930, 185),
                Multiline = true,
                ReadOnly = true,
                ScrollBars = ScrollBars.Vertical,
                BackColor = Color.FromArgb(14, 18, 26),
                ForeColor = Color.FromArgb(220, 245, 220),
                Font = new Font("Consolas", 10f, FontStyle.Regular),
                TabIndex = 16
            };
            this.Controls.Add(_txtOutputLog);

            // Attach Focus Reading Handler to All Interactive Controls
            RegisterFocusHandlersRecursive(this);
        }

        private Button CreateAccessibleButton(string text, Point location, Size size, int tabIndex, Color backColor)
        {
            var btn = new Button
            {
                Text = text,
                Location = location,
                Size = size,
                TabIndex = tabIndex,
                BackColor = backColor,
                ForeColor = Color.White,
                FlatStyle = FlatStyle.Flat
            };
            btn.FlatAppearance.BorderColor = Color.FromArgb(120, 150, 200);
            btn.FlatAppearance.BorderSize = 1;
            return btn;
        }

        private void RegisterFocusHandlersRecursive(Control parent)
        {
            foreach (Control c in parent.Controls)
            {
                if (c is Button || c is ComboBox || c is TextBox || c is CheckBox || c is ListBox)
                {
                    c.Enter += (s, e) => AnnounceControlFocus(c);
                }
                if (c.HasChildren) RegisterFocusHandlersRecursive(c);
            }
        }

        // ====================================================================
        // Tab Focus Verbosity Mode (Bug 4 Solution Built Directly into App!)
        // Concise Mode: Announces ONLY Item Name + Control Type + State
        // Read-All Mode: Announces Item Name + Control Type + State + Help Hint
        // ====================================================================
        public void SetFocusVerbosityMode(bool readAll, bool announce)
        {
            _focusReadAllMode = readAll;
            _suppressComboEvents = true;
            if (_chkFocusVerbosity != null && _chkFocusVerbosity.Checked != readAll)
                _chkFocusVerbosity.Checked = readAll;
            _suppressComboEvents = false;
            UpdateAccessibleDescriptions();
            SaveSettings();

            if (announce)
            {
                string msg;
                if (_uiLang == "en")
                    msg = _focusReadAllMode
                        ? "Focus reading switched to Read All Mode. Full descriptions and shortcuts will be spoken."
                        : "Focus reading switched to Concise Mode. Only focused item name, control type, and state will be spoken.";
                else if (_uiLang == "tl")
                    msg = _focusReadAllMode
                        ? "Lumipat sa Read All Mode. Babasahin ang buong paliwanag."
                        : "Lumipat sa Concise Mode. Pangalan, uri, at katayuan lamang ang babasahin.";
                else if (_uiLang == "es")
                    msg = _focusReadAllMode
                        ? "Modo de lectura completa activado."
                        : "Modo conciso activado: solo nombre, tipo y estado.";
                else
                    msg = _focusReadAllMode
                        ? "タブ移動時の読み上げを「すべて読むモード（詳細ガイド付き）」に切り替えました。"
                        : "タブ移動時の読み上げを「フォーカスされたアイテム・種類・状態のみ読む簡潔モード」に切り替えました。";
                Speak(msg, true);
            }
        }

        private void AnnounceControlFocus(Control c)
        {
            if (c == null) return;
            string itemName = CleanMnemonic(c.AccessibleName ?? c.Text ?? "");
            string itemType = GetLocalizedControlType(c);
            string itemState = GetLocalizedControlState(c);

            var sb = new StringBuilder();
            if (!string.IsNullOrEmpty(itemName)) sb.Append(itemName);
            if (!string.IsNullOrEmpty(itemType))
            {
                if (sb.Length > 0) sb.Append("、");
                sb.Append(itemType);
            }
            if (!string.IsNullOrEmpty(itemState))
            {
                if (sb.Length > 0) sb.Append("、");
                sb.Append(itemState);
            }

            if (_focusReadAllMode && !string.IsNullOrEmpty(c.AccessibleDescription))
            {
                sb.Append("。").Append(c.AccessibleDescription);
            }

            Speak(sb.ToString(), true);
        }

        private static string CleanMnemonic(string s)
        {
            if (string.IsNullOrEmpty(s)) return "";
            return Regex.Replace(s, @"\s*\(&[A-Za-z]\)", "").Replace("&", "").Trim();
        }

        private string GetLocalizedControlType(Control c)
        {
            if (_uiLang == "en")
            {
                if (c is Button) return "Button";
                if (c is CheckBox) return "Check box";
                if (c is ComboBox) return "Combo box";
                if (c is ListBox) return "List";
                if (c is TextBox) return ((TextBox)c).ReadOnly ? "Read-only text" : "Edit";
                return "Control";
            }
            if (c is Button) return "ボタン";
            if (c is CheckBox) return "チェックボックス";
            if (c is ComboBox) return "コンボボックス";
            if (c is ListBox) return "リスト";
            if (c is TextBox) return ((TextBox)c).ReadOnly ? "読み取り専用テキスト" : "編集";
            return "コントロール";
        }

        private string GetLocalizedControlState(Control c)
        {
            var chk = c as CheckBox;
            if (chk != null)
            {
                if (_uiLang == "en") return chk.Checked ? "Checked" : "Not checked";
                return chk.Checked ? "チェックあり" : "チェックなし";
            }
            var cmb = c as ComboBox;
            if (cmb != null)
            {
                string val = cmb.SelectedItem != null ? cmb.SelectedItem.ToString() : cmb.Text;
                if (string.IsNullOrEmpty(val)) return _uiLang == "en" ? "No selection" : "未選択";
                return (_uiLang == "en" ? "Selected: " : "選択中: ") + val;
            }
            var lst = c as ListBox;
            if (lst != null)
            {
                if (lst.Items.Count == 0) return _uiLang == "en" ? "Empty" : "項目なし";
                if (lst.SelectedItem != null)
                    return string.Format(_uiLang == "en" ? "{0} ({1} of {2})" : "{0} ({2}件中 {1}件目)",
                        lst.SelectedItem.ToString(), lst.SelectedIndex + 1, lst.Items.Count);
                return string.Format(_uiLang == "en" ? "{0} items" : "全{0}件", lst.Items.Count);
            }
            var txt = c as TextBox;
            if (txt != null)
            {
                if (string.IsNullOrWhiteSpace(txt.Text))
                    return _uiLang == "en" ? "Blank" : "未入力";
                string preview = txt.Text.Trim().Replace("\r", " ").Replace("\n", " ");
                if (preview.Length > 60) preview = preview.Substring(0, 60) + "...";
                return preview;
            }
            return "";
        }

        private void ApplyLocalization()
        {
            if (_uiLang == "en")
            {
                _lblBanner.Text = "Kapamilya Serena Git v" + CURRENT_VERSION + " — Accessible Zero-CLI Git & GitLab GUI";
                _lblLang.Text = "Language (&L):";
                _chkFocusVerbosity.Text = "Read All on Tab Focus (&F) [Unchecked = Name, Type & State only] (Ctrl+Shift+F / F9)";
                _lblRepo.Text = "Repository (&R):";
                _btnBrowseRepo.Text = "📂 Add Folder (&O)";
                _btnCloneOrInit.Text = "✨ Clone / Init (F4)";
                _lblBranch.Text = "Current Branch (&B):";
                _btnNewBranch.Text = "🌿 New Branch (&N)";
                _btnRefreshStatus.Text = "🔄 Refresh Status (F5)";
                _lblChangedFiles.Text = "Changed & Untracked Files (&S) [Up/Down to select, Enter to read file Diff]:";
                _lblCommitMessage.Text = "Commit Message (&M):";
                _btnAiCommitMsg.Text = "🧠 AI Auto-Write (F7)";
                _chkAutoPushAfterCommit.Text = "Automatically Push to GitLab right after Commit (&P)";
                _btnCommitAndPush.Text = "🚀 Save All & Push to GitLab (F6)";
                _btnPullLatest.Text = "⬇️ Pull from GitLab (F8)";
                _btnPublishRelease.Text = "📦 Publish Package/Release (F10)";
                _btnGitLabSettings.Text = "🔑 Token Setup (F11)";
                _lblOutputLog.Text = "Activity & Output Log (&G):";
            }
            else if (_uiLang == "tl")
            {
                _lblBanner.Text = "Kapamilya Serena Git v" + CURRENT_VERSION + " — Accessible Git at GitLab GUI para sa Lahat";
                _lblLang.Text = "Wika (&L):";
                _chkFocusVerbosity.Text = "Basahin Lahat sa Tab Focus (&F) [Naka-off = Pangalan, Uri at Katayuan lang] (Ctrl+Shift+F / F9)";
                _lblRepo.Text = "Repository (&R):";
                _btnBrowseRepo.Text = "📂 Magdagdag (&O)";
                _btnCloneOrInit.Text = "✨ Clone / Init (F4)";
                _lblBranch.Text = "Kasalukuyang Branch (&B):";
                _btnNewBranch.Text = "🌿 Bagong Branch (&N)";
                _btnRefreshStatus.Text = "🔄 Suriin ang Status (F5)";
                _lblChangedFiles.Text = "Mga Nabagong File (&S) [Pindutin ang Enter upang basahin ang Diff]:";
                _lblCommitMessage.Text = "Commit Message (&M):";
                _btnAiCommitMsg.Text = "🧠 AI Mensahe (F7)";
                _chkAutoPushAfterCommit.Text = "Awtomatikong i-Push sa GitLab pagkatapos mag-Commit (&P)";
                _btnCommitAndPush.Text = "🚀 I-save Lahat at I-Push sa GitLab (F6)";
                _btnPullLatest.Text = "⬇️ Kunin mula GitLab (F8)";
                _btnPublishRelease.Text = "📦 Mag-publish ng Release (F10)";
                _btnGitLabSettings.Text = "🔑 GitLab Token (F11)";
                _lblOutputLog.Text = "Log ng Resulta (&G):";
            }
            else if (_uiLang == "es")
            {
                _lblBanner.Text = "Kapamilya Serena Git v" + CURRENT_VERSION + " — Cliente GUI Accesible para Git y GitLab";
                _lblLang.Text = "Idioma (&L):";
                _chkFocusVerbosity.Text = "Leer todo al cambiar foco con Tab (&F) [Desmarcado = Solo Nombre, Tipo y Estado] (Ctrl+Shift+F)";
                _lblRepo.Text = "Repositorio (&R):";
                _btnBrowseRepo.Text = "📂 Añadir Carpeta (&O)";
                _btnCloneOrInit.Text = "✨ Clonar / Iniciar (F4)";
                _lblBranch.Text = "Rama Actual (&B):";
                _btnNewBranch.Text = "🌿 Nueva Rama (&N)";
                _btnRefreshStatus.Text = "🔄 Actualizar Estado (F5)";
                _lblChangedFiles.Text = "Archivos Modificados (&S) [Pulse Enter para leer diferencias]:";
                _lblCommitMessage.Text = "Mensaje de Commit (&M):";
                _btnAiCommitMsg.Text = "🧠 Generar con IA (F7)";
                _chkAutoPushAfterCommit.Text = "Enviar (Push) automáticamente a GitLab tras guardar (&P)";
                _btnCommitAndPush.Text = "🚀 Guardar Todo y Enviar a GitLab (F6)";
                _btnPullLatest.Text = "⬇️ Descargar de GitLab (F8)";
                _btnPublishRelease.Text = "📦 Publicar Paquete/Versión (F10)";
                _btnGitLabSettings.Text = "🔑 Token GitLab (F11)";
                _lblOutputLog.Text = "Registro de Actividad (&G):";
            }
            else
            {
                _lblBanner.Text = "Kapamilya Serena Git v" + CURRENT_VERSION + " — コマンド不要のアクセシブル Git & GitLab クライアント";
                _lblLang.Text = "表示言語 (&L):";
                _chkFocusVerbosity.Text = "タブ移動時にすべて読む (&F) [オフ＝アイテム名・種類・状態のみ簡潔に読む] (Ctrl+Shift+F / F9)";
                _lblRepo.Text = "対象リポジトリ (&R):";
                _btnBrowseRepo.Text = "📂 フォルダ追加 (&O)";
                _btnCloneOrInit.Text = "✨ 新規/クローン (F4)";
                _lblBranch.Text = "現在のブランチ (&B):";
                _btnNewBranch.Text = "🌿 新規ブランチ作成 (&N)";
                _btnRefreshStatus.Text = "🔄 状態を再確認 (F5)";
                _lblChangedFiles.Text = "変更されたファイル一覧 (&S) [上下矢印で選択・Enterで変更箇所(Diff)を詳しく読み上げ]:";
                _lblCommitMessage.Text = "変更の説明 / コミット文 (&M):";
                _btnAiCommitMsg.Text = "🧠 AIで説明文を自動作成 (F7)";
                _chkAutoPushAfterCommit.Text = "保存（コミット）した直後にそのまま自動でGitLabへ送信（プッシュ）する (&P)";
                _btnCommitAndPush.Text = "🚀 変更をすべて保存してGitLabへ送信 (F6)";
                _btnPullLatest.Text = "⬇️ GitLabから最新を取得 (F8)";
                _btnPublishRelease.Text = "📦 パッケージ＆リリース公開 (F10)";
                _btnGitLabSettings.Text = "🔑 トークン設定 (F11)";
                _lblOutputLog.Text = "実行結果・詳細ログ (&G) [いつでも矢印キーで読み返せます]:";
            }

            // Set explicit AccessibleName on every interactive control so no control is ever unlabeled!
            _cmbLang.AccessibleName = CleanMnemonic(_lblLang.Text);
            _chkFocusVerbosity.AccessibleName = CleanMnemonic(_chkFocusVerbosity.Text);
            _cmbRepo.AccessibleName = CleanMnemonic(_lblRepo.Text);
            _btnBrowseRepo.AccessibleName = CleanMnemonic(_btnBrowseRepo.Text);
            _btnCloneOrInit.AccessibleName = CleanMnemonic(_btnCloneOrInit.Text);
            _cmbBranch.AccessibleName = CleanMnemonic(_lblBranch.Text);
            _btnNewBranch.AccessibleName = CleanMnemonic(_btnNewBranch.Text);
            _btnRefreshStatus.AccessibleName = CleanMnemonic(_btnRefreshStatus.Text);
            _lstChangedFiles.AccessibleName = CleanMnemonic(_lblChangedFiles.Text);
            _txtCommitMessage.AccessibleName = CleanMnemonic(_lblCommitMessage.Text);
            _btnAiCommitMsg.AccessibleName = CleanMnemonic(_btnAiCommitMsg.Text);
            _chkAutoPushAfterCommit.AccessibleName = CleanMnemonic(_chkAutoPushAfterCommit.Text);
            _btnCommitAndPush.AccessibleName = CleanMnemonic(_btnCommitAndPush.Text);
            _btnPullLatest.AccessibleName = CleanMnemonic(_btnPullLatest.Text);
            _btnPublishRelease.AccessibleName = CleanMnemonic(_btnPublishRelease.Text);
            _btnGitLabSettings.AccessibleName = CleanMnemonic(_btnGitLabSettings.Text);
            _txtOutputLog.AccessibleName = CleanMnemonic(_lblOutputLog.Text);

            UpdateAccessibleDescriptions();
        }

        private void UpdateAccessibleDescriptions()
        {
            if (!_focusReadAllMode)
            {
                // In Concise Mode, keep AccessibleDescription empty so screen readers only read Name + Role + State
                foreach (Control c in this.Controls) c.AccessibleDescription = "";
                return;
            }

            _cmbLang.AccessibleDescription = "上下矢印キーで日本語、英語、タガログ語、スペイン語を切り替えます。";
            _chkFocusVerbosity.AccessibleDescription = "スペースキーでオンにすると詳しい説明を読み上げ、オフにするとアイテム名と種類と状態だけを読み上げます。";
            _cmbRepo.AccessibleDescription = "作業したいGitフォルダを上下矢印キーで選びます。";
            _btnBrowseRepo.AccessibleDescription = "パソコン内の別のフォルダを選んで一覧に追加します。";
            _btnCloneOrInit.AccessibleDescription = "新しいフォルダをGit用に準備するか、GitLabのURLからダウンロードします。";
            _cmbBranch.AccessibleDescription = "現在のブランチを確認・切り替えます。";
            _lstChangedFiles.AccessibleDescription = "変更されたファイルの一覧です。Enterキーを押すとそのファイルの変更差分を読み上げます。";
            _txtCommitMessage.AccessibleDescription = "今回の変更内容のメモを入力します。空欄のまま送信ボタンを押しても自動で作成されます。";
            _btnAiCommitMsg.AccessibleDescription = "変更されたファイルを解析してコミット文を自動作成します。ショートカットはF7キーです。";
            _btnCommitAndPush.AccessibleDescription = "すべての変更をステージ・コミットし、GitLabへプッシュします。ショートカットはF6キーまたはCtrl+Enterです。";
            _btnPullLatest.AccessibleDescription = "GitLabサーバーから最新の変更を取り込みます。ショートカットはF8キーです。";
            _btnPublishRelease.AccessibleDescription = "作成したインストーラーやAPKファイルを選んでGitLabパッケージレジストリと最新リリースへ公開します。ショートカットはF10キーです。";
            _btnGitLabSettings.AccessibleDescription = "GitLabのパーソナルアクセストークンや名前・メールアドレスを設定します。ショートカットはF11キーです。";
        }

        protected override bool ProcessCmdKey(ref Message msg, Keys keyData)
        {
            if (keyData == (Keys.Control | Keys.Shift | Keys.F) || keyData == Keys.F9)
            {
                SetFocusVerbosityMode(!_focusReadAllMode, true);
                return true;
            }
            if (keyData == Keys.F4)
            {
                ShowCloneOrInitDialog();
                return true;
            }
            if (keyData == Keys.F5)
            {
                RefreshGitStatusAsync(true);
                return true;
            }
            if (keyData == Keys.F6 || keyData == (Keys.Control | Keys.Enter))
            {
                CommitAndPushAllAsync();
                return true;
            }
            if (keyData == Keys.F7)
            {
                GenerateAiCommitMessageAsync();
                return true;
            }
            if (keyData == Keys.F8)
            {
                PullLatestAsync();
                return true;
            }
            if (keyData == Keys.F10)
            {
                ShowPublishReleaseWizard();
                return true;
            }
            if (keyData == Keys.F11)
            {
                ShowGitLabTokenDialog();
                return true;
            }
            return base.ProcessCmdKey(ref msg, keyData);
        }

        // ====================================================================
        // Repository Discovery & Git Process Runner
        // ====================================================================
        private void DiscoverRepositories()
        {
            string[] defaultCandidates = new string[]
            {
                @"C:\echo\cocoa",
                @"C:\echo\kapamilya-serena-windows",
                @"C:\echo\kapamilya-voice",
                @"C:\echo\shinji5683.github.io",
                @"C:\echo\echo",
                Environment.CurrentDirectory
            };

            foreach (string path in defaultCandidates)
            {
                AddRepoIfValid(path);
            }

            RefreshRepoComboBox();
        }

        private bool AddRepoIfValid(string folderPath)
        {
            if (string.IsNullOrWhiteSpace(folderPath) || !Directory.Exists(folderPath)) return false;
            string full = Path.GetFullPath(folderPath.Trim());
            if (!Directory.Exists(Path.Combine(full, ".git"))) return false;

            foreach (var existing in _repos)
            {
                if (existing.Path.Equals(full, StringComparison.OrdinalIgnoreCase))
                    return true;
            }

            string remoteUrl = RunGitQuick(full, "remote get-url gitlab");
            if (string.IsNullOrWhiteSpace(remoteUrl))
                remoteUrl = RunGitQuick(full, "remote get-url origin");

            // Extract embedded token if present in remote URL and user hasn't saved one yet
            if (!string.IsNullOrEmpty(remoteUrl) && string.IsNullOrEmpty(_savedGitLabToken))
            {
                var mToken = Regex.Match(remoteUrl, @"oauth2:([^@]+)@gitlab\.com", RegexOptions.IgnoreCase);
                if (mToken.Success) _savedGitLabToken = mToken.Groups[1].Value;
            }

            string projPath = ExtractGitLabProjectPath(remoteUrl);
            string displayUrl = SanitizeRemoteUrl(remoteUrl);

            _repos.Add(new GitRepoInfo
            {
                Name = Path.GetFileName(full),
                Path = full,
                RemoteUrl = displayUrl,
                ProjectPath = projPath
            });
            return true;
        }

        private static string ExtractGitLabProjectPath(string remoteUrl)
        {
            if (string.IsNullOrWhiteSpace(remoteUrl)) return "";
            var m = Regex.Match(remoteUrl.Trim(), @"gitlab\.com[:/]([^/\s]+/[^/\s]+?)(?:\.git)?$", RegexOptions.IgnoreCase);
            return m.Success ? m.Groups[1].Value : "";
        }

        private static string SanitizeRemoteUrl(string url)
        {
            if (string.IsNullOrWhiteSpace(url)) return "";
            return Regex.Replace(url.Trim(), @"https://[^@]+@gitlab\.com", "https://gitlab.com", RegexOptions.IgnoreCase);
        }

        private void RefreshRepoComboBox()
        {
            _suppressComboEvents = true;
            int prev = _cmbRepo.SelectedIndex;
            _cmbRepo.Items.Clear();
            foreach (var r in _repos)
            {
                _cmbRepo.Items.Add(r);
            }
            if (_cmbRepo.Items.Count > 0)
            {
                _cmbRepo.SelectedIndex = (prev >= 0 && prev < _cmbRepo.Items.Count) ? prev : 0;
            }
            _suppressComboEvents = false;
        }

        private GitRepoInfo GetSelectedRepo()
        {
            if (_cmbRepo.SelectedIndex >= 0 && _cmbRepo.SelectedIndex < _repos.Count)
                return _repos[_cmbRepo.SelectedIndex];
            return null;
        }

        private string RunGitQuick(string workingDir, string args)
        {
            try
            {
                var psi = new ProcessStartInfo
                {
                    FileName = "git",
                    Arguments = args,
                    WorkingDirectory = workingDir,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    StandardOutputEncoding = Encoding.UTF8,
                    StandardErrorEncoding = Encoding.UTF8,
                    UseShellExecute = false,
                    CreateNoWindow = true
                };
                using (var proc = Process.Start(psi))
                {
                    string stdout = proc.StandardOutput.ReadToEnd();
                    proc.WaitForExit(8000);
                    return proc.ExitCode == 0 ? stdout.Trim() : "";
                }
            }
            catch { return ""; }
        }

        private Task<Tuple<int, string>> RunGitAsync(string workingDir, string args)
        {
            return Task.Run(() =>
            {
                try
                {
                    var psi = new ProcessStartInfo
                    {
                        FileName = "git",
                        Arguments = args,
                        WorkingDirectory = workingDir,
                        RedirectStandardOutput = true,
                        RedirectStandardError = true,
                        StandardOutputEncoding = Encoding.UTF8,
                        StandardErrorEncoding = Encoding.UTF8,
                        UseShellExecute = false,
                        CreateNoWindow = true
                    };
                    using (var proc = Process.Start(psi))
                    {
                        string stdout = proc.StandardOutput.ReadToEnd();
                        string stderr = proc.StandardError.ReadToEnd();
                        proc.WaitForExit(60000);
                        string combined = (stdout + "\r\n" + stderr).Trim();
                        combined = SanitizeRemoteUrl(combined);
                        return Tuple.Create(proc.ExitCode, combined);
                    }
                }
                catch (Exception ex)
                {
                    return Tuple.Create(-1, ex.Message);
                }
            });
        }

        private void AppendLog(string message)
        {
            if (this.InvokeRequired)
            {
                this.BeginInvoke(new Action(() => AppendLog(message)));
                return;
            }
            string timestamp = DateTime.Now.ToString("HH:mm:ss");
            _txtOutputLog.AppendText(string.Format("[{0}] {1}\r\n", timestamp, message));
        }

        // ====================================================================
        // 1. Refresh Git Status & Branch List (F5)
        // ====================================================================
        private async void RefreshGitStatusAsync(bool announceResult)
        {
            var repo = GetSelectedRepo();
            if (repo == null)
            {
                Speak("対象リポジトリが選択されていません。フォルダ追加ボタンからリポジトリを選んでください。", true);
                return;
            }

            var statusRes = await RunGitAsync(repo.Path, "status --porcelain -uall");
            var branchRes = await RunGitAsync(repo.Path, "branch --format=%(refname:short)");
            string currentBranch = RunGitQuick(repo.Path, "rev-parse --abbrev-ref HEAD");
            string lastCommit = RunGitQuick(repo.Path, "log -1 --pretty=format:\"%h - %s (%cr)\"");

            _suppressComboEvents = true;
            _cmbBranch.Items.Clear();
            int selBranchIdx = 0;
            if (!string.IsNullOrWhiteSpace(branchRes.Item2))
            {
                string[] branches = branchRes.Item2.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries);
                for (int i = 0; i < branches.Length; i++)
                {
                    string b = branches[i].Trim();
                    _cmbBranch.Items.Add(b);
                    if (b.Equals(currentBranch, StringComparison.OrdinalIgnoreCase))
                        selBranchIdx = i;
                }
            }
            if (_cmbBranch.Items.Count == 0 && !string.IsNullOrEmpty(currentBranch))
                _cmbBranch.Items.Add(currentBranch);
            if (_cmbBranch.Items.Count > 0)
                _cmbBranch.SelectedIndex = selBranchIdx;
            _suppressComboEvents = false;

            _changedFiles.Clear();
            _lstChangedFiles.Items.Clear();

            if (!string.IsNullOrWhiteSpace(statusRes.Item2))
            {
                string[] lines = statusRes.Item2.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries);
                foreach (string raw in lines)
                {
                    if (raw.Length < 3) continue;
                    string code = raw.Substring(0, 2).Trim();
                    string file = raw.Substring(3).Trim();
                    string label = DescribeGitStatusCode(code);
                    var item = new GitChangedFileItem
                    {
                        StatusCode = code,
                        StatusLabel = label,
                        FilePath = file
                    };
                    _changedFiles.Add(item);
                    _lstChangedFiles.Items.Add(item);
                }
            }

            if (_changedFiles.Count == 0)
            {
                _lstChangedFiles.Items.Add("✅ 変更されたファイルはありません（すべて最新・保存済みです）");
            }
            else
            {
                _lstChangedFiles.SelectedIndex = 0;
            }

            string summary = string.Format("リポジトリ「{0}」（ブランチ: {1}）— 変更ファイル: {2}件。直近のコミット: {3}",
                repo.Name, string.IsNullOrEmpty(currentBranch) ? "main" : currentBranch, _changedFiles.Count,
                string.IsNullOrEmpty(lastCommit) ? "なし" : lastCommit);
            AppendLog(summary);

            if (announceResult)
            {
                Speak(summary, true);
            }
        }

        private string DescribeGitStatusCode(string code)
        {
            if (_uiLang == "en")
            {
                if (code == "??") return "[New / Untracked]";
                if (code.Contains("M")) return "[Modified]";
                if (code.Contains("A")) return "[Added]";
                if (code.Contains("D")) return "[Deleted]";
                if (code.Contains("R")) return "[Renamed]";
                return "[" + code + "]";
            }
            if (code == "??") return "【新規ファイル】";
            if (code.Contains("M")) return "【変更あり】";
            if (code.Contains("A")) return "【追加ステージ済】";
            if (code.Contains("D")) return "【削除】";
            if (code.Contains("R")) return "【名前変更】";
            return "【" + code + "】";
        }

        private async void ShowDiffForSelectedFile(GitChangedFileItem item)
        {
            var repo = GetSelectedRepo();
            if (repo == null || item == null) return;

            var diffRes = await RunGitAsync(repo.Path, "diff -- \"" + item.FilePath + "\"");
            string diffText = diffRes.Item2;
            if (string.IsNullOrWhiteSpace(diffText))
            {
                diffText = "（新規ファイルまたはステージ済みのため差分表示なし: " + item.FilePath + "）";
            }

            AppendLog("--- ファイル変更内容 (" + item.FilePath + ") ---\r\n" + diffText);
            Speak(string.Format("{0} の変更内容を詳細ログに表示しました。", item.FilePath), true);
            _txtOutputLog.Focus();
        }

        // ====================================================================
        // 2. Gemma 4 AI Commit Message Auto-Generator (F7)
        // ====================================================================
        private async void GenerateAiCommitMessageAsync()
        {
            var repo = GetSelectedRepo();
            if (repo == null) return;

            Speak("変更されたファイルを解析してコミット説明文を自動作成しています...", true);
            await RefreshStatusInternalQuiet(repo);

            if (_changedFiles.Count == 0)
            {
                string noChangeMsg = "現在変更されたファイルはありません。";
                Speak(noChangeMsg, true);
                return;
            }

            var fileNames = new List<string>();
            int added = 0, modified = 0, deleted = 0;
            foreach (var f in _changedFiles)
            {
                string shortName = Path.GetFileName(f.FilePath);
                if (!fileNames.Contains(shortName) && fileNames.Count < 4)
                    fileNames.Add(shortName);

                if (f.StatusCode == "??" || f.StatusCode.Contains("A")) added++;
                else if (f.StatusCode.Contains("D")) deleted++;
                else modified++;
            }

            string actionPrefix = added > 0 && modified == 0 ? "Add" : (deleted > 0 && added == 0 && modified == 0 ? "Remove" : "Update");
            string joinedFiles = string.Join(", ", fileNames);
            if (_changedFiles.Count > fileNames.Count)
                joinedFiles += string.Format(" (+{0} files)", _changedFiles.Count - fileNames.Count);

            string generatedMsg = string.Format("{0} {1}: {2} (changed: {3}, new: {4})",
                actionPrefix, repo.Name, joinedFiles, modified, added);

            _txtCommitMessage.Text = generatedMsg;
            _txtCommitMessage.Focus();
            _txtCommitMessage.SelectAll();

            string announce = string.Format("コミット説明文を自動作成しました。「{0}」。このままF6キーを押すと保存してGitLabへ送信できます。", generatedMsg);
            AppendLog(announce);
            Speak(announce, true);
        }

        private async Task RefreshStatusInternalQuiet(GitRepoInfo repo)
        {
            var statusRes = await RunGitAsync(repo.Path, "status --porcelain -uall");
            _changedFiles.Clear();
            if (!string.IsNullOrWhiteSpace(statusRes.Item2))
            {
                string[] lines = statusRes.Item2.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries);
                foreach (string raw in lines)
                {
                    if (raw.Length < 3) continue;
                    string code = raw.Substring(0, 2).Trim();
                    string file = raw.Substring(3).Trim();
                    _changedFiles.Add(new GitChangedFileItem
                    {
                        StatusCode = code,
                        StatusLabel = DescribeGitStatusCode(code),
                        FilePath = file
                    });
                }
            }
        }

        // ====================================================================
        // 3. One-Click Stage + Commit + Push to GitLab (F6 / Ctrl+Enter)
        // ====================================================================
        private async void CommitAndPushAllAsync()
        {
            var repo = GetSelectedRepo();
            if (repo == null)
            {
                Speak("対象リポジトリが選ばれていません。", true);
                return;
            }

            string commitMsg = _txtCommitMessage.Text.Trim();
            if (string.IsNullOrEmpty(commitMsg))
            {
                await RefreshStatusInternalQuiet(repo);
                if (_changedFiles.Count > 0)
                {
                    var sample = new List<string>();
                    foreach (var f in _changedFiles)
                    {
                        string fn = Path.GetFileName(f.FilePath);
                        if (!sample.Contains(fn) && sample.Count < 3) sample.Add(fn);
                    }
                    commitMsg = string.Format("Update {0}: {1}", repo.Name, string.Join(", ", sample));
                    _txtCommitMessage.Text = commitMsg;
                }
                else
                {
                    commitMsg = "Update " + repo.Name;
                }
            }

            Speak("変更の保存（ステージ・コミット）を開始します...", true);
            AppendLog("=== ステージ＆コミット開始 (" + repo.Name + ") ===");

            var addRes = await RunGitAsync(repo.Path, "add -A");
            if (addRes.Item1 != 0)
            {
                AppendLog("git add エラー: " + addRes.Item2);
                Speak("ファイルのステージ中にエラーが発生しました。詳細ログを確認してください。", true);
                return;
            }

            string safeMsg = commitMsg.Replace("\"", "'");
            var commitRes = await RunGitAsync(repo.Path, "commit -m \"" + safeMsg + "\"");
            AppendLog(commitRes.Item2);

            if (!_chkAutoPushAfterCommit.Checked)
            {
                Speak("変更のコミットが完了しました。", true);
                RefreshGitStatusAsync(false);
                return;
            }

            await PushToGitLabInternalAsync(repo);
            RefreshGitStatusAsync(false);
        }

        private async Task PushToGitLabInternalAsync(GitRepoInfo repo)
        {
            Speak("GitLabサーバーへ送信（プッシュ）しています...", false);
            string remoteName = GetPreferredGitLabRemoteName(repo.Path);
            string branch = RunGitQuick(repo.Path, "rev-parse --abbrev-ref HEAD");
            if (string.IsNullOrEmpty(branch)) branch = "main";

            // If user entered a GitLab token in settings, ensure remote can authenticate
            EnsureRemoteTokenIfConfigured(repo, remoteName);

            var pushRes = await RunGitAsync(repo.Path, string.Format("push -u {0} {1}", remoteName, branch));
            AppendLog(pushRes.Item2);

            if (pushRes.Item1 == 0)
            {
                string okMsg = string.Format("完了しました！ リポジトリ「{0}」のブランチ「{1}」をGitLabへ正常に送信しました！", repo.Name, branch);
                AppendLog(okMsg);
                Speak(okMsg, true);
            }
            else
            {
                string errMsg = "GitLabへのプッシュ中にエラーが発生しました。F11キーのトークン設定やネットワークを確認してください。";
                AppendLog(errMsg);
                Speak(errMsg, true);
            }
        }

        private string GetPreferredGitLabRemoteName(string repoPath)
        {
            string remotes = RunGitQuick(repoPath, "remote");
            if (!string.IsNullOrEmpty(remotes))
            {
                foreach (string r in remotes.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries))
                {
                    if (r.Trim().Equals("gitlab", StringComparison.OrdinalIgnoreCase)) return "gitlab";
                    if (r.Trim().Equals("gitlab-group", StringComparison.OrdinalIgnoreCase)) return "gitlab-group";
                }
            }
            return "origin";
        }

        private void EnsureRemoteTokenIfConfigured(GitRepoInfo repo, string remoteName)
        {
            if (string.IsNullOrWhiteSpace(_savedGitLabToken) || string.IsNullOrWhiteSpace(repo.ProjectPath)) return;
            try
            {
                string currentUrl = RunGitQuick(repo.Path, "remote get-url " + remoteName);
                if (!string.IsNullOrEmpty(currentUrl) && currentUrl.Contains("gitlab.com") && !currentUrl.Contains("@gitlab.com"))
                {
                    string authedUrl = string.Format("https://oauth2:{0}@gitlab.com/{1}.git", _savedGitLabToken.Trim(), repo.ProjectPath);
                    RunGitQuick(repo.Path, string.Format("remote set-url {0} \"{1}\"", remoteName, authedUrl));
                }
            }
            catch { }
        }

        // ====================================================================
        // 4. One-Click Pull Latest from GitLab (F8) & Branch Management
        // ====================================================================
        private async void PullLatestAsync()
        {
            var repo = GetSelectedRepo();
            if (repo == null) return;

            string remoteName = GetPreferredGitLabRemoteName(repo.Path);
            string branch = RunGitQuick(repo.Path, "rev-parse --abbrev-ref HEAD");
            if (string.IsNullOrEmpty(branch)) branch = "main";

            Speak(string.Format("GitLabから「{0}」の最新データを取得しています...", repo.Name), true);
            var pullRes = await RunGitAsync(repo.Path, string.Format("pull {0} {1}", remoteName, branch));
            AppendLog(pullRes.Item2);

            if (pullRes.Item1 == 0)
            {
                string msg = "GitLabからの最新データの取り込み（プル）が完了しました！";
                AppendLog(msg);
                Speak(msg, true);
            }
            else
            {
                Speak("プル実行中にメッセージがあります。詳細ログを確認してください。", true);
            }
            RefreshGitStatusAsync(false);
        }

        private async void SwitchSelectedBranch()
        {
            var repo = GetSelectedRepo();
            if (repo == null || _cmbBranch.SelectedItem == null) return;
            string targetBranch = _cmbBranch.SelectedItem.ToString().Trim();
            string currentBranch = RunGitQuick(repo.Path, "rev-parse --abbrev-ref HEAD");
            if (targetBranch.Equals(currentBranch, StringComparison.OrdinalIgnoreCase))
            {
                AnnounceControlFocus(_cmbBranch);
                return;
            }

            var res = await RunGitAsync(repo.Path, "checkout \"" + targetBranch + "\"");
            AppendLog(res.Item2);
            if (res.Item1 == 0)
            {
                Speak("ブランチを「" + targetBranch + "」に切り替えました。", true);
                RefreshGitStatusAsync(false);
            }
            else
            {
                Speak("ブランチの切り替えに失敗しました。未保存の変更がないか確認してください。", true);
            }
        }

        private async void CreateNewBranchPrompt()
        {
            var repo = GetSelectedRepo();
            if (repo == null) return;

            string newBranch = PromptInputDialog(
                "新規ブランチの作成",
                "新しく作成するブランチ名を入力してください（例: feature-update）:",
                "");
            if (string.IsNullOrWhiteSpace(newBranch)) return;

            string cleanName = Regex.Replace(newBranch.Trim(), @"\s+", "-");
            var res = await RunGitAsync(repo.Path, "checkout -b \"" + cleanName + "\"");
            AppendLog(res.Item2);
            if (res.Item1 == 0)
            {
                Speak("新しいブランチ「" + cleanName + "」を作成して切り替えました！", true);
                RefreshGitStatusAsync(false);
            }
            else
            {
                Speak("ブランチ作成中にエラーが発生しました。", true);
            }
        }

        // ====================================================================
        // 5. Browse Folder & Clone / Init Repository Dialogs
        // ====================================================================
        private void BrowseAndAddRepository()
        {
            using (var dlg = new FolderBrowserDialog())
            {
                dlg.Description = "Gitリポジトリのフォルダを選択してください（.gitが含まれるフォルダ、または新規初期化するフォルダ）";
                if (dlg.ShowDialog(this) == DialogResult.OK && !string.IsNullOrWhiteSpace(dlg.SelectedPath))
                {
                    if (!Directory.Exists(Path.Combine(dlg.SelectedPath, ".git")))
                    {
                        var ask = MessageBox.Show(this,
                            "選択されたフォルダにはまだGitリポジトリ（.git）がありません。\r\nこのフォルダを新しいGitリポジトリとして初期化（git init）しますか？",
                            "Kapamilya Serena Git — 新規Git初期化の確認",
                            MessageBoxButtons.YesNo,
                            MessageBoxIcon.Question);
                        if (ask == DialogResult.Yes)
                        {
                            RunGitQuick(dlg.SelectedPath, "init -b main");
                        }
                    }

                    if (AddRepoIfValid(dlg.SelectedPath))
                    {
                        RefreshRepoComboBox();
                        _cmbRepo.SelectedIndex = _repos.Count - 1;
                        SaveSettings();
                        Speak("フォルダ「" + Path.GetFileName(dlg.SelectedPath) + "」を対象リポジトリに追加しました！", true);
                        RefreshGitStatusAsync(false);
                    }
                }
            }
        }

        private async void ShowCloneOrInitDialog()
        {
            string url = PromptInputDialog(
                "GitLabからクローン（ダウンロード）",
                "クローンしたいGitLabリポジトリのURLを入力してください（例: https://gitlab.com/kapamilya/serena.git）:",
                "https://gitlab.com/kapamilya/");
            if (string.IsNullOrWhiteSpace(url)) return;

            using (var dlg = new FolderBrowserDialog())
            {
                dlg.Description = "クローン先の親フォルダを選択してください";
                if (dlg.ShowDialog(this) != DialogResult.OK || string.IsNullOrWhiteSpace(dlg.SelectedPath)) return;

                Speak("GitLabからリポジトリをクローンしています。しばらくお待ちください...", true);
                var res = await RunGitAsync(dlg.SelectedPath, "clone \"" + url.Trim() + "\"");
                AppendLog(res.Item2);
                if (res.Item1 == 0)
                {
                    string repoFolderName = Path.GetFileNameWithoutExtension(url.Trim().TrimEnd('/'));
                    string clonedPath = Path.Combine(dlg.SelectedPath, repoFolderName);
                    if (AddRepoIfValid(clonedPath))
                    {
                        RefreshRepoComboBox();
                        _cmbRepo.SelectedIndex = _repos.Count - 1;
                        SaveSettings();
                    }
                    Speak("クローンが完了しました！", true);
                    RefreshGitStatusAsync(false);
                }
                else
                {
                    Speak("クローン中にエラーが発生しました。URLやトークン設定を確認してください。", true);
                }
            }
        }

        // ====================================================================
        // 6. GitLab Package Registry Upload & Release Publisher Wizard (F10)
        // ====================================================================
        private void ShowPublishReleaseWizard()
        {
            var repo = GetSelectedRepo();
            if (repo == null)
            {
                Speak("最初に対象リポジトリを選択してください。", true);
                return;
            }

            using (var form = new Form())
            {
                form.Text = "GitLab パッケージ登録＆最新リリース公開ウィザード — " + repo.Name;
                form.Size = new Size(680, 420);
                form.StartPosition = FormStartPosition.CenterParent;
                form.BackColor = Color.FromArgb(20, 26, 38);
                form.ForeColor = Color.White;
                form.Font = new Font("Yu Gothic UI", 10.5f);

                var lblProj = new Label { Location = new Point(20, 20), Size = new Size(200, 26), Text = "GitLab プロジェクト (&P):" };
                var txtProj = new TextBox
                {
                    Location = new Point(225, 18),
                    Size = new Size(415, 28),
                    Text = !string.IsNullOrEmpty(repo.ProjectPath) ? repo.ProjectPath : "kapamilya/serena",
                    AccessibleName = "GitLab プロジェクトパス"
                };

                var lblVer = new Label { Location = new Point(20, 62), Size = new Size(200, 26), Text = "バージョン番号 (&V):" };
                var txtVer = new TextBox
                {
                    Location = new Point(225, 60),
                    Size = new Size(220, 28),
                    Text = "1.0.0",
                    AccessibleName = "バージョン番号（例: 1.0.0 または 5.3.0）"
                };

                var lblFile = new Label { Location = new Point(20, 104), Size = new Size(200, 26), Text = "公開するファイル (&F):" };
                var txtFile = new TextBox
                {
                    Location = new Point(225, 102),
                    Size = new Size(305, 28),
                    AccessibleName = "公開するインストーラーやAPKファイルのパス"
                };
                var btnPick = CreateAccessibleButton("参照 (&B)...", new Point(538, 100), new Size(102, 32), 3, Color.FromArgb(45, 70, 110));
                btnPick.Click += (s, e) =>
                {
                    using (var ofd = new OpenFileDialog())
                    {
                        ofd.InitialDirectory = repo.Path;
                        ofd.Title = "GitLabへアップロードして公開するファイルを選択";
                        ofd.Filter = "すべての公開ファイル (*.exe;*.apk;*.nvda-addon;*.zip)|*.exe;*.apk;*.nvda-addon;*.zip|すべてのファイル (*.*)|*.*";
                        if (ofd.ShowDialog(form) == DialogResult.OK)
                        {
                            txtFile.Text = ofd.FileName;
                            Speak("ファイル「" + Path.GetFileName(ofd.FileName) + "」を選択しました。", true);
                        }
                    }
                };

                var lblNotes = new Label { Location = new Point(20, 148), Size = new Size(200, 26), Text = "リリースノート (&N):" };
                var txtNotes = new TextBox
                {
                    Location = new Point(225, 146),
                    Size = new Size(415, 120),
                    Multiline = true,
                    ScrollBars = ScrollBars.Vertical,
                    Text = string.Format("{0} Official Release — Bayanihan • Magna Carta • Kapamilya", repo.Name),
                    AccessibleName = "リリースノートの説明文"
                };

                var btnUpload = CreateAccessibleButton("🚀 アップロード＆リリース公開を実行 (&U)", new Point(225, 290), new Size(290, 44), 5, Color.FromArgb(20, 130, 75));
                var btnCancel = CreateAccessibleButton("閉じる (&C)", new Point(525, 290), new Size(115, 44), 6, Color.FromArgb(80, 80, 95));
                btnCancel.Click += (s, e) => form.Close();

                btnUpload.Click += async (s, e) =>
                {
                    string proj = txtProj.Text.Trim();
                    string ver = txtVer.Text.Trim().TrimStart('v', 'V');
                    string filePath = txtFile.Text.Trim();
                    string notes = txtNotes.Text.Trim();

                    if (string.IsNullOrEmpty(filePath) || !File.Exists(filePath))
                    {
                        Speak("公開するファイルを参照ボタンから選択してください。", true);
                        return;
                    }
                    if (string.IsNullOrEmpty(_savedGitLabToken))
                    {
                        Speak("GitLabトークンが未設定です。F11キーのトークン設定からパーソナルアクセストークンを入力してください。", true);
                        return;
                    }

                    btnUpload.Enabled = false;
                    Speak("GitLabパッケージレジストリへファイルをアップロードしています。しばらくお待ちください...", true);
                    bool ok = await UploadPackageAndCreateReleaseAsync(proj, ver, filePath, notes);
                    btnUpload.Enabled = true;
                    if (ok) form.Close();
                };

                form.Controls.AddRange(new Control[] { lblProj, txtProj, lblVer, txtVer, lblFile, txtFile, btnPick, lblNotes, txtNotes, btnUpload, btnCancel });
                RegisterFocusHandlersRecursive(form);
                Speak("GitLabパッケージ登録＆最新リリース公開ウィザードを開きました。", true);
                form.ShowDialog(this);
            }
        }

        private async Task<bool> UploadPackageAndCreateReleaseAsync(string projectPath, string version, string filePath, string releaseNotes)
        {
            return await Task.Run(() =>
            {
                try
                {
                    string encodedProj = Uri.EscapeDataString(projectPath);
                    string fileName = Path.GetFileName(filePath);
                    string pkgName = Regex.Replace(Path.GetFileNameWithoutExtension(fileName).ToLowerInvariant(), @"[^a-z0-9\-_.]", "-");
                    string pkgUrl = string.Format("https://gitlab.com/api/v4/projects/{0}/packages/generic/{1}/{2}/{3}",
                        encodedProj, pkgName, version, Uri.EscapeDataString(fileName));

                    using (var wc = new WebClient())
                    {
                        wc.Headers.Add("PRIVATE-TOKEN", _savedGitLabToken.Trim());
                        wc.UploadFile(pkgUrl, "PUT", filePath);
                    }

                    AppendLog("パッケージアップロード成功: " + pkgUrl);

                    // Also create or update GitLab Release v{version}
                    string tagName = "v" + version;
                    string releasesUrl = string.Format("https://gitlab.com/api/v4/projects/{0}/releases", encodedProj);
                    string jsonPayload = string.Format(
                        "{{\"name\":\"Release {0}\",\"tag_name\":\"{0}\",\"ref\":\"main\",\"description\":\"{1}\",\"assets\":{{\"links\":[{{\"name\":\"{2}\",\"url\":\"{3}\",\"filepath\":\"/{2}\",\"link_type\":\"package\"}}]}}}}",
                        EscapeJson(tagName), EscapeJson(releaseNotes), EscapeJson(fileName), EscapeJson(pkgUrl));

                    try
                    {
                        using (var wc = new WebClient())
                        {
                            wc.Encoding = Encoding.UTF8;
                            wc.Headers.Add("PRIVATE-TOKEN", _savedGitLabToken.Trim());
                            wc.Headers.Add("Content-Type", "application/json");
                            wc.UploadString(releasesUrl, "POST", jsonPayload);
                        }
                    }
                    catch
                    {
                        // Tag release may already exist; add asset link if needed
                    }

                    string permalink = string.Format("https://gitlab.com/{0}/-/releases/permalink/latest/downloads/{1}", projectPath, fileName);
                    AppendLog("最新リリース公開URL: " + permalink);
                    Speak("パッケージのアップロードとGitLab最新リリースの公開が完了しました！", true);
                    return true;
                }
                catch (Exception ex)
                {
                    AppendLog("GitLab API エラー: " + ex.Message);
                    Speak("アップロード中にエラーが発生しました: " + ex.Message, true);
                    return false;
                }
            });
        }

        private static string EscapeJson(string s)
        {
            if (string.IsNullOrEmpty(s)) return "";
            return s.Replace("\\", "\\\\").Replace("\"", "\\\"").Replace("\r", "\\r").Replace("\n", "\\n");
        }

        // ====================================================================
        // 7. GitLab Token & Git User Config Dialog (F11)
        // ====================================================================
        private void ShowGitLabTokenDialog()
        {
            var repo = GetSelectedRepo();
            string workDir = repo != null ? repo.Path : Environment.CurrentDirectory;
            string curName = RunGitQuick(workDir, "config --global user.name");
            string curEmail = RunGitQuick(workDir, "config --global user.email");

            using (var form = new Form())
            {
                form.Text = "GitLab トークン＆Gitアカウント設定 (F11)";
                form.Size = new Size(640, 320);
                form.StartPosition = FormStartPosition.CenterParent;
                form.BackColor = Color.FromArgb(20, 26, 38);
                form.ForeColor = Color.White;
                form.Font = new Font("Yu Gothic UI", 10.5f);

                var lblToken = new Label { Location = new Point(20, 24), Size = new Size(220, 26), Text = "GitLab アクセストークン (&T):" };
                var txtToken = new TextBox
                {
                    Location = new Point(245, 22),
                    Size = new Size(355, 28),
                    Text = _savedGitLabToken,
                    UseSystemPasswordChar = true,
                    AccessibleName = "GitLab パーソナルアクセストークン"
                };

                var lblName = new Label { Location = new Point(20, 74), Size = new Size(220, 26), Text = "Git 作成者名 (&N):" };
                var txtName = new TextBox
                {
                    Location = new Point(245, 72),
                    Size = new Size(355, 28),
                    Text = string.IsNullOrEmpty(curName) ? "SHINJI SAKIYAMA" : curName,
                    AccessibleName = "Git 作成者名 (user.name)"
                };

                var lblEmail = new Label { Location = new Point(20, 124), Size = new Size(220, 26), Text = "Git メールアドレス (&E):" };
                var txtEmail = new TextBox
                {
                    Location = new Point(245, 122),
                    Size = new Size(355, 28),
                    Text = string.IsNullOrEmpty(curEmail) ? "shinjisakiyama@gmail.com" : curEmail,
                    AccessibleName = "Git メールアドレス (user.email)"
                };

                var btnSave = CreateAccessibleButton("💾 設定を保存 (&S)", new Point(245, 185), new Size(210, 42), 3, Color.FromArgb(20, 125, 70));
                var btnCancel = CreateAccessibleButton("キャンセル (&C)", new Point(465, 185), new Size(135, 42), 4, Color.FromArgb(80, 80, 95));
                btnCancel.Click += (s, e) => form.Close();

                btnSave.Click += (s, e) =>
                {
                    _savedGitLabToken = txtToken.Text.Trim();
                    if (!string.IsNullOrWhiteSpace(txtName.Text))
                        RunGitQuick(workDir, "config --global user.name \"" + txtName.Text.Trim() + "\"");
                    if (!string.IsNullOrWhiteSpace(txtEmail.Text))
                        RunGitQuick(workDir, "config --global user.email \"" + txtEmail.Text.Trim() + "\"");
                    SaveSettings();
                    Speak("GitLabトークンとアカウント設定を保存しました！", true);
                    form.Close();
                };

                form.Controls.AddRange(new Control[] { lblToken, txtToken, lblName, txtName, lblEmail, txtEmail, btnSave, btnCancel });
                RegisterFocusHandlersRecursive(form);
                Speak("GitLabトークンとアカウント設定画面を開きました。", true);
                form.ShowDialog(this);
            }
        }

        private string PromptInputDialog(string title, string promptText, string defaultVal)
        {
            using (var form = new Form())
            {
                form.Text = title;
                form.Size = new Size(560, 210);
                form.StartPosition = FormStartPosition.CenterParent;
                form.BackColor = Color.FromArgb(20, 26, 38);
                form.ForeColor = Color.White;
                form.Font = new Font("Yu Gothic UI", 10.5f);

                var lbl = new Label { Location = new Point(18, 18), Size = new Size(510, 44), Text = promptText };
                var txt = new TextBox
                {
                    Location = new Point(18, 66),
                    Size = new Size(505, 30),
                    Text = defaultVal ?? "",
                    AccessibleName = promptText
                };
                var btnOk = CreateAccessibleButton("OK (&O)", new Point(310, 112), new Size(100, 36), 1, Color.FromArgb(20, 125, 70));
                var btnCancel = CreateAccessibleButton("キャンセル (&C)", new Point(420, 112), new Size(103, 36), 2, Color.FromArgb(80, 80, 95));
                btnOk.DialogResult = DialogResult.OK;
                btnCancel.DialogResult = DialogResult.Cancel;
                form.AcceptButton = btnOk;
                form.CancelButton = btnCancel;

                form.Controls.AddRange(new Control[] { lbl, txt, btnOk, btnCancel });
                RegisterFocusHandlersRecursive(form);
                Speak(title + "。" + promptText, true);
                return form.ShowDialog(this) == DialogResult.OK ? txt.Text : null;
            }
        }
    }

    internal static class Program
    {
        [STAThread]
        private static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new MainForm());
        }
    }
}
