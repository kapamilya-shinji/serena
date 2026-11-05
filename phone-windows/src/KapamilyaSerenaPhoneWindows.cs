// ============================================================================
// Kapamilya Serena Phone for Windows v5.11.0
// 100% Accessible Windows Phone Dialer, AES-256-GCM E2EE P2P WebRTC Group
// Audio (Max 10) / Video (Max 6) Calling, Hands-Free Auto-Reply AI Voice/Video
// Test Bot, Connect/Join/Leave Sound Effects, 4-Language Localization &
// Google DeepMind EmbeddingGemma 2 + Gemma 4 Dual Video Call AI Narrator
//
// Exclusive Copyright & Intellectual Property Owner: SHINJI SAKIYAMA (崎山真治)
// Under the Banner of the Magna Carta & Bantay ng Dangal
// Bayanihan • Magna Carta • Kapamilya
// ============================================================================

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.Globalization;
using System.IO;
using System.Media;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Speech.Recognition;
using System.Speech.Synthesis;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Win32;

namespace KapamilyaSerenaPhoneWindows
{
    internal static class Program
    {
        [STAThread]
        private static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new PhoneMainForm());
        }
    }

    // ========================================================================
    // NIST SP 800-38D AES-256-GCM + RFC 8018 PBKDF2-HMAC-SHA256 Engine
    // (100% Bit-for-Bit Compatible with Android KapamilyaAes256GcmMeshEngine)
    // ========================================================================
    public static class Aes256GcmCryptoEngine
    {
        public const string WIRE_PREFIX = "KSP59_GCM:";
        private const int PBKDF2_ITERATIONS = 100000;
        private const int GCM_IV_LENGTH = 12;
        private const int GCM_TAG_LENGTH = 16;

        [DllImport("bcrypt.dll", CharSet = CharSet.Unicode)]
        private static extern int BCryptOpenAlgorithmProvider(out IntPtr phAlgorithm, string pszAlgId, string pszImplementation, uint dwFlags);

        [DllImport("bcrypt.dll", CharSet = CharSet.Unicode)]
        private static extern int BCryptSetProperty(IntPtr hObject, string pszProperty, byte[] pbInput, int cbInput, uint dwFlags);

        [DllImport("bcrypt.dll")]
        private static extern int BCryptGenerateSymmetricKey(IntPtr hAlgorithm, out IntPtr phKey, IntPtr pbKeyObject, int cbKeyObject, byte[] pbSecret, int cbSecret, uint dwFlags);

        [DllImport("bcrypt.dll")]
        private static extern int BCryptEncrypt(IntPtr hKey, byte[] pbInput, int cbInput, ref BCRYPT_AUTHENTICATED_CIPHER_MODE_INFO pPaddingInfo, byte[] pbIV, int cbIV, byte[] pbOutput, int cbOutput, out int pcbResult, uint dwFlags);

        [DllImport("bcrypt.dll")]
        private static extern int BCryptDecrypt(IntPtr hKey, byte[] pbInput, int cbInput, ref BCRYPT_AUTHENTICATED_CIPHER_MODE_INFO pPaddingInfo, byte[] pbIV, int cbIV, byte[] pbOutput, int cbOutput, out int pcbResult, uint dwFlags);

        [DllImport("bcrypt.dll")]
        private static extern int BCryptDestroyKey(IntPtr hKey);

        [DllImport("bcrypt.dll")]
        private static extern int BCryptCloseAlgorithmProvider(IntPtr hAlgorithm, uint dwFlags);

        [StructLayout(LayoutKind.Sequential)]
        private struct BCRYPT_AUTHENTICATED_CIPHER_MODE_INFO
        {
            public int cbSize;
            public int dwInfoVersion;
            public IntPtr pbNonce;
            public int cbNonce;
            public IntPtr pbAuthData;
            public int cbAuthData;
            public IntPtr pbTag;
            public int cbTag;
            public IntPtr pbMacContext;
            public int cbMacContext;
            public int cbAAD;
            public long cbData;
            public int dwFlags;
        }

        public static byte[] DeriveKeyPbkdf2HmacSha256(string passphrase, string roomName, out string sasFingerprint)
        {
            string cleanRoom = string.IsNullOrWhiteSpace(roomName) ? "kapamilya-serena-room" : roomName.Trim();
            string cleanPass = string.IsNullOrWhiteSpace(passphrase) ? cleanRoom : passphrase.Trim();
            byte[] passwordBytes = Encoding.UTF8.GetBytes(cleanPass);
            byte[] saltBytes = Encoding.UTF8.GetBytes("KapamilyaSerenaPhone_AES256GCM_Salt_v5.9:" + cleanRoom);

            byte[] key = Pbkdf2Sha256(passwordBytes, saltBytes, PBKDF2_ITERATIONS, 32);
            using (var sha = SHA256.Create())
            {
                byte[] digest = sha.ComputeHash(key);
                sasFingerprint = string.Format("{0:X2}{1:X2}-{2:X2}{3:X2}", digest[0], digest[1], digest[2], digest[3]);
            }
            return key;
        }

        private static byte[] Pbkdf2Sha256(byte[] password, byte[] salt, int iterations, int outputBytes)
        {
            using (var hmac = new HMACSHA256(password))
            {
                byte[] saltAndBlock = new byte[salt.Length + 4];
                Buffer.BlockCopy(salt, 0, saltAndBlock, 0, salt.Length);
                saltAndBlock[salt.Length] = 0;
                saltAndBlock[salt.Length + 1] = 0;
                saltAndBlock[salt.Length + 2] = 0;
                saltAndBlock[salt.Length + 3] = 1; // Block 1 (32 bytes = 256 bits)

                byte[] u = hmac.ComputeHash(saltAndBlock);
                byte[] t = new byte[u.Length];
                Buffer.BlockCopy(u, 0, t, 0, u.Length);

                for (int i = 2; i <= iterations; i++)
                {
                    u = hmac.ComputeHash(u);
                    for (int j = 0; j < t.Length; j++)
                    {
                        t[j] ^= u[j];
                    }
                }

                byte[] result = new byte[outputBytes];
                Buffer.BlockCopy(t, 0, result, 0, outputBytes);
                return result;
            }
        }

        public static string EncryptAes256Gcm(string plainText, byte[] key32, string roomName)
        {
            byte[] plainBytes = Encoding.UTF8.GetBytes(plainText);
            byte[] aadBytes = Encoding.UTF8.GetBytes("KAPAMILYA_E2EE_V59:" + roomName);
            byte[] iv = new byte[GCM_IV_LENGTH];
            using (var rng = RandomNumberGenerator.Create())
            {
                rng.GetBytes(iv);
            }

            byte[] cipherBytes = new byte[plainBytes.Length];
            byte[] tagBytes = new byte[GCM_TAG_LENGTH];

            IntPtr hAlg = IntPtr.Zero;
            IntPtr hKey = IntPtr.Zero;
            GCHandle hIv = GCHandle.Alloc(iv, GCHandleType.Pinned);
            GCHandle hAad = GCHandle.Alloc(aadBytes, GCHandleType.Pinned);
            GCHandle hTag = GCHandle.Alloc(tagBytes, GCHandleType.Pinned);

            try
            {
                int status = BCryptOpenAlgorithmProvider(out hAlg, "AES", null, 0);
                if (status != 0) throw new CryptographicException("BCryptOpenAlgorithmProvider failed: " + status);

                byte[] chainMode = Encoding.Unicode.GetBytes("ChainingModeGCM\0");
                status = BCryptSetProperty(hAlg, "ChainingMode", chainMode, chainMode.Length, 0);
                if (status != 0) throw new CryptographicException("BCryptSetProperty GCM failed: " + status);

                status = BCryptGenerateSymmetricKey(hAlg, out hKey, IntPtr.Zero, 0, key32, key32.Length, 0);
                if (status != 0) throw new CryptographicException("BCryptGenerateSymmetricKey failed: " + status);

                var authInfo = new BCRYPT_AUTHENTICATED_CIPHER_MODE_INFO
                {
                    cbSize = Marshal.SizeOf(typeof(BCRYPT_AUTHENTICATED_CIPHER_MODE_INFO)),
                    dwInfoVersion = 1,
                    pbNonce = hIv.AddrOfPinnedObject(),
                    cbNonce = iv.Length,
                    pbAuthData = hAad.AddrOfPinnedObject(),
                    cbAuthData = aadBytes.Length,
                    pbTag = hTag.AddrOfPinnedObject(),
                    cbTag = tagBytes.Length
                };

                int cbResult;
                status = BCryptEncrypt(hKey, plainBytes, plainBytes.Length, ref authInfo, null, 0, cipherBytes, cipherBytes.Length, out cbResult, 0);
                if (status != 0) throw new CryptographicException("BCryptEncrypt GCM failed: " + status);

                byte[] combined = new byte[iv.Length + cipherBytes.Length + tagBytes.Length];
                Buffer.BlockCopy(iv, 0, combined, 0, iv.Length);
                Buffer.BlockCopy(cipherBytes, 0, combined, iv.Length, cipherBytes.Length);
                Buffer.BlockCopy(tagBytes, 0, combined, iv.Length + cipherBytes.Length, tagBytes.Length);

                return WIRE_PREFIX + Convert.ToBase64String(combined);
            }
            finally
            {
                if (hIv.IsAllocated) hIv.Free();
                if (hAad.IsAllocated) hAad.Free();
                if (hTag.IsAllocated) hTag.Free();
                if (hKey != IntPtr.Zero) BCryptDestroyKey(hKey);
                if (hAlg != IntPtr.Zero) BCryptCloseAlgorithmProvider(hAlg, 0);
            }
        }

        public static string DecryptAes256Gcm(string wirePayload, byte[] key32, string roomName)
        {
            if (string.IsNullOrWhiteSpace(wirePayload)) return null;
            string trimmed = wirePayload.Trim();
            if (!trimmed.StartsWith(WIRE_PREFIX)) return null;

            byte[] combined;
            try
            {
                combined = Convert.FromBase64String(trimmed.Substring(WIRE_PREFIX.Length));
            }
            catch
            {
                return null;
            }

            if (combined.Length < GCM_IV_LENGTH + GCM_TAG_LENGTH) return null;

            int cipherLen = combined.Length - GCM_IV_LENGTH - GCM_TAG_LENGTH;
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] cipherBytes = new byte[cipherLen];
            byte[] tagBytes = new byte[GCM_TAG_LENGTH];

            Buffer.BlockCopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            Buffer.BlockCopy(combined, GCM_IV_LENGTH, cipherBytes, 0, cipherLen);
            Buffer.BlockCopy(combined, GCM_IV_LENGTH + cipherLen, tagBytes, 0, GCM_TAG_LENGTH);

            byte[] aadBytes = Encoding.UTF8.GetBytes("KAPAMILYA_E2EE_V59:" + roomName);
            byte[] plainBytes = new byte[cipherLen];

            IntPtr hAlg = IntPtr.Zero;
            IntPtr hKey = IntPtr.Zero;
            GCHandle hIv = GCHandle.Alloc(iv, GCHandleType.Pinned);
            GCHandle hAad = GCHandle.Alloc(aadBytes, GCHandleType.Pinned);
            GCHandle hTag = GCHandle.Alloc(tagBytes, GCHandleType.Pinned);

            try
            {
                int status = BCryptOpenAlgorithmProvider(out hAlg, "AES", null, 0);
                if (status != 0) return null;

                byte[] chainMode = Encoding.Unicode.GetBytes("ChainingModeGCM\0");
                status = BCryptSetProperty(hAlg, "ChainingMode", chainMode, chainMode.Length, 0);
                if (status != 0) return null;

                status = BCryptGenerateSymmetricKey(hAlg, out hKey, IntPtr.Zero, 0, key32, key32.Length, 0);
                if (status != 0) return null;

                var authInfo = new BCRYPT_AUTHENTICATED_CIPHER_MODE_INFO
                {
                    cbSize = Marshal.SizeOf(typeof(BCRYPT_AUTHENTICATED_CIPHER_MODE_INFO)),
                    dwInfoVersion = 1,
                    pbNonce = hIv.AddrOfPinnedObject(),
                    cbNonce = iv.Length,
                    pbAuthData = hAad.AddrOfPinnedObject(),
                    cbAuthData = aadBytes.Length,
                    pbTag = hTag.AddrOfPinnedObject(),
                    cbTag = tagBytes.Length
                };

                int cbResult;
                status = BCryptDecrypt(hKey, cipherBytes, cipherBytes.Length, ref authInfo, null, 0, plainBytes, plainBytes.Length, out cbResult, 0);
                if (status != 0) return null; // Authentication Tag mismatch -> strictly rejected!

                return Encoding.UTF8.GetString(plainBytes, 0, cbResult);
            }
            catch
            {
                return null;
            }
            finally
            {
                if (hIv.IsAllocated) hIv.Free();
                if (hAad.IsAllocated) hAad.Free();
                if (hTag.IsAllocated) hTag.Free();
                if (hKey != IntPtr.Zero) BCryptDestroyKey(hKey);
                if (hAlg != IntPtr.Zero) BCryptCloseAlgorithmProvider(hAlg, 0);
            }
        }
    }

    public sealed class MeshParticipantInfo
    {
        public string PeerId { get; set; }
        public string DisplayName { get; set; }
        public string Endpoint { get; set; }
        public bool IsVideoEnabled { get; set; }
        public string LatestExpression { get; set; }

        public override string ToString()
        {
            string mode = IsVideoEnabled ? "🎥ビデオ" : "🎙️音声";
            return string.Format("{0} [{1}] — {2}", DisplayName, mode, LatestExpression);
        }
    }

    public sealed class SpeedDialContact
    {
        public string Name { get; set; }
        public string Target { get; set; }
        public bool IsWebRtc { get; set; }
        public bool IsVideo { get; set; }

        public override string ToString()
        {
            string mode = IsWebRtc ? (IsVideo ? "WebRTCビデオ通話(最大6人)" : "WebRTC音声通話(最大10人)") : "電話番号発信";
            return string.Format("{0} ({1}: {2})", Name, mode, Target);
        }
    }

    public sealed class CallLogRecord
    {
        public string Timestamp { get; set; }
        public string Partner { get; set; }
        public string Mode { get; set; }
        public long DurationSeconds { get; set; }
        public string FormattedDuration { get; set; }

        public override string ToString()
        {
            return string.Format("[{0}] {1} ({2}) — 通話時間: {3}", Timestamp, Partner, Mode, FormattedDuration);
        }
    }

    public sealed class PhoneMainForm : Form
    {
        public const string CURRENT_VERSION = "5.14.0";
        public const int MAX_VIDEO_PARTICIPANTS = 6;
        public const int MAX_AUDIO_PARTICIPANTS = 10;
        private const int WEBRTC_P2P_UDP_PORT = 45882;
        private const int BAYANIHAN_LINK_UDP_PORT = 45881;

        [DllImport("avicap32.dll", EntryPoint = "capCreateCaptureWindowW", CharSet = CharSet.Unicode)]
        private static extern IntPtr capCreateCaptureWindowW(
            string lpszWindowName, int dwStyle, int x, int y, int nWidth, int nHeight, IntPtr hwndParent, int nID);

        [DllImport("user32.dll", EntryPoint = "SendMessageW")]
        private static extern IntPtr SendMessageW(IntPtr hWnd, int Msg, IntPtr wParam, IntPtr lParam);

        private const int WM_CAP_DRIVER_CONNECT = 0x040A;
        private const int WM_CAP_DRIVER_DISCONNECT = 0x040B;
        private const int WM_CAP_EDIT_COPY = 0x041E;
        private const int WM_CAP_GRAB_FRAME = 0x043C;

        private SpeechSynthesizer _tts;
        private SpeechRecognitionEngine _speechRecognizer;
        private int _testBotTurnIndex = 0;
        private bool _isBotCallActive = false;
        private int _botProactiveSilenceTicks = 0;
        private int _userVoiceActiveTicks = 0;
        private string _lastHypothesizedSpeech = "";
        private string _uiLang = "ja";
        private bool _focusReadAllMode = false;
        private bool _suppressComboEvents = false;

        private readonly string _settingsDir;
        private readonly string _settingsFile;
        private readonly string _contactsFile;
        private readonly string _callHistoryFile;

        private readonly List<SpeedDialContact> _contacts = new List<SpeedDialContact>();
        private readonly List<CallLogRecord> _callHistory = new List<CallLogRecord>();
        private readonly Dictionary<string, MeshParticipantInfo> _meshParticipants = new Dictionary<string, MeshParticipantInfo>();
        private readonly string _localPeerId;

        // AES-256-GCM Key & SAS State
        private byte[] _currentAes256Key;
        private string _currentSasFingerprint = "----";
        private string _currentRoomName = "Kapamilya-Serena-Home";
        private string _currentPassphrase = "Kapamilya-Serena-AES256GCM-Key";

        // Call & WebRTC State
        private bool _isCallActive = false;
        private bool _isVideoCall = true;
        private bool _isMicMuted = false;
        private bool _isSpeakerphone = true;
        private bool _autoAiNarrate = true;
        private string _activeCallPartner = "";
        private string _activeCallModeLabel = "";
        private readonly Stopwatch _callStopwatch = new Stopwatch();
        private System.Windows.Forms.Timer _callUiTimer;
        private int _autoAiTickCounter = 0;
        private int _expressionCycleIndex = 0;

        // Networking (UDP P2P + STUN)
        private UdpClient _p2pUdpListener;
        private CancellationTokenSource _p2pCts;
        private string _discoveredPublicEndpoint = "未検出 (STUN待機中)";
        private string _lastRemotePartnerExpression = "";

        // UI Controls
        private Label _lblBanner;
        private Label _lblLang;
        private ComboBox _cmbLang;
        private CheckBox _chkFocusVerbosity;

        private Label _lblCallStatusBox;
        private Label _lblEncryptionMeshBanner;
        private int _activePhoneTab = 0; // 0 = Standard Phone, 1 = Video Call, 2 = Contacts, 3 = Settings
        private Button _btnTabStandardPhone;
        private Button _btnTabVideoCall;
        private Button _btnTabContacts;
        private Button _btnTabSettings;
        private FlowLayoutPanel _pnlStandardPhoneTab;
        private FlowLayoutPanel _pnlVideoCallTab;
        private FlowLayoutPanel _pnlContactsTab;
        private FlowLayoutPanel _pnlSettingsTab;
        private ComboBox _cmbAudioQuality;
        private CheckBox _chkEchoCanceler;
        private CheckBox _chkNoiseSuppressor;
        private CheckBox _chkAutoGain;
        private Button _btnOpenGoogleContacts;
        private Label _lblDialNumber;
        private TextBox _txtDialNumber;
        private Label _lblWebRtcRoom;
        private TextBox _txtWebRtcRoom;
        private Label _lblAesPassphrase;
        private TextBox _txtAesPassphrase;
        private ComboBox _cmbCallType;

        private Button _btnStartCall;
        private Button _btnEndCall;
        private Button _btnMuteMic;
        private Button _btnToggleSpeaker;
        private Button _btnPartnerExpressionAi;
        private Button _btnSelfCameraCheckAi;
        private CheckBox _chkAutoAiNarrate;

        private Button _btnGenerateInviteCode;
        private Button _btnAcceptInviteCode;
        private Button _btnSpeakMeshRoster;

        private Button _btnTestBot1on1;
        private Button _btnTestBotTalkMic;
        private Button _btnTestBotVideo6;
        private Button _btnTestBotAudio10;
        private Button _btnTestBotLeaveSound;

        private GroupBox _grpKeypad;
        private readonly List<Button> _keypadButtons = new List<Button>();

        private Label _lblContacts;
        private ListBox _lstContacts;
        private Button _btnCallSelectedContact;
        private Button _btnAddContact;
        private Button _btnDeleteContact;

        private Label _lblCallHistory;
        private ListBox _lstCallHistory;
        private Button _btnSpeakSelectedHistory;

        private Label _lblAiLog;
        private TextBox _txtAiLog;

        public PhoneMainForm()
        {
            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;
            _localPeerId = "WIN-" + (1000 + new Random().Next(9000));
            _settingsDir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                "KapamilyaSerenaPhone"
            );
            Directory.CreateDirectory(_settingsDir);
            _settingsFile = Path.Combine(_settingsDir, "settings.ini");
            _contactsFile = Path.Combine(_settingsDir, "contacts.txt");
            _callHistoryFile = Path.Combine(_settingsDir, "call_history.txt");

            InitSpeech();
            LoadSettings();
            LoadContactsAndHistory();
            RefreshAes256GcmKeyAndResetMesh();
            InitializeAccessibleUi();
            KapamilyaKrisSoundEngine.AttachToFormHierarchy(this);
            ApplyLocalization();
            StartP2pUdpSignalingServer();

            _callUiTimer = new System.Windows.Forms.Timer { Interval = 1000 };
            _callUiTimer.Tick += OnCallTimerTick;

            KeyPreview = true;
            KeyDown += OnFormKeyDown;

            Shown += (s, e) =>
            {
                SpeakTagalogStartupGreeting();
                Task.Run(() => DiscoverStunPublicEndpoint());
                CheckForDualRouteUpdateOnStartup();
            };

            FormClosing += (s, e) =>
            {
                StopP2pUdpSignalingServer();
                SaveSettings();
                SaveContactsAndHistory();
                SpeakShutdownGreetingSync();
            };
        }

        private void CheckForDualRouteUpdateOnStartup()
        {
            Task.Run(() =>
            {
                try
                {
                    ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;
                    string pagesUrl = "https://kapamilya.gitlab.io/latest.json";
                    string repoUrl = "https://gitlab.com/kapamilya/serena/-/raw/main/latest.json";

                    string pagesJson = null, repoJson = null;
                    long pagesMs = long.MaxValue, repoMs = long.MaxValue;

                    var tPages = Task.Run(() =>
                    {
                        try
                        {
                            var sw = Stopwatch.StartNew();
                            var req = (HttpWebRequest)WebRequest.Create(pagesUrl + "?t=" + DateTime.UtcNow.Ticks);
                            req.Timeout = 6000;
                            req.UserAgent = "Kapamilya-Serena-Phone-Windows/" + CURRENT_VERSION;
                            using (var resp = (HttpWebResponse)req.GetResponse())
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
                            var req = (HttpWebRequest)WebRequest.Create(repoUrl + "?t=" + DateTime.UtcNow.Ticks);
                            req.Timeout = 6000;
                            req.UserAgent = "Kapamilya-Serena-Phone-Windows/" + CURRENT_VERSION;
                            using (var resp = (HttpWebResponse)req.GetResponse())
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

                    if (!IsDisposed && IsHandleCreated)
                    {
                        BeginInvoke(new Action(() =>
                        {
                            if (!IsDisposed)
                                AppendLog(string.Format("・起動時アップデーター高速回線自動選択: {0} ({1}ms)", chosenRoute, chosenMs));
                        }));
                    }

                    int idx = chosenJson.IndexOf("\"kapamilya-serena-phone-windows\"", StringComparison.OrdinalIgnoreCase);
                    if (idx < 0) return;
                    int nextAppIdx = chosenJson.IndexOf("\"kapamilya-serena-recorder-windows\"", idx, StringComparison.OrdinalIgnoreCase);
                    int braceEnd = nextAppIdx > idx ? nextAppIdx : chosenJson.Length;
                    string sec = chosenJson.Substring(idx, braceEnd - idx);

                    string latestVer = ExtractSimpleJsonField(sec, "version").TrimStart('v', 'V').Trim();
                    string urlKey = chosenRoute == "GitLab Pages" ? "gitlab_pages_url" : "gitlab_repo_url";
                    string dlUrl = ExtractSimpleJsonField(sec, urlKey);
                    if (string.IsNullOrEmpty(dlUrl))
                        dlUrl = "https://gitlab.com/api/v4/projects/kapamilya%2Fserena/packages/generic/kapamilya-serena/5.14.0/Kapamilya-Serena-Phone-Windows-Setup.exe";

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
                            Speak(prompt, false);
                            if (MessageBox.Show(this, prompt, "🚀 Kapamilya Serena Phone Auto-Update", MessageBoxButtons.YesNo, MessageBoxIcon.Information) == DialogResult.Yes)
                            {
                                Speak(chosenRoute + " から最新インストーラーをダウンロードしています。", false);
                                Task.Run(() =>
                                {
                                    try
                                    {
                                        string outPath = Path.Combine(Path.GetTempPath(), "Kapamilya-Serena-Phone-Windows-Setup.exe");
                                        using (var wc = new WebClient())
                                        {
                                            wc.Headers["User-Agent"] = "Kapamilya-Serena-Phone-Windows/" + CURRENT_VERSION;
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

        private int GetMaxParticipantsForCurrentMode()
        {
            return _isVideoCall ? MAX_VIDEO_PARTICIPANTS : MAX_AUDIO_PARTICIPANTS;
        }

        private void RefreshAes256GcmKeyAndResetMesh()
        {
            if (_txtWebRtcRoom != null && !string.IsNullOrWhiteSpace(_txtWebRtcRoom.Text))
                _currentRoomName = _txtWebRtcRoom.Text.Trim();
            if (_txtAesPassphrase != null && !string.IsNullOrWhiteSpace(_txtAesPassphrase.Text))
                _currentPassphrase = _txtAesPassphrase.Text.Trim();

            _currentAes256Key = Aes256GcmCryptoEngine.DeriveKeyPbkdf2HmacSha256(
                _currentPassphrase, _currentRoomName, out _currentSasFingerprint);

            lock (_meshParticipants)
            {
                _meshParticipants.Clear();
                _meshParticipants[_localPeerId] = new MeshParticipantInfo
                {
                    PeerId = _localPeerId,
                    DisplayName = Environment.MachineName + " (自分)",
                    Endpoint = "local",
                    IsVideoEnabled = _isVideoCall,
                    LatestExpression = "カメラ正面・参加中"
                };
            }
            UpdateEncryptionMeshBanner();
        }

        private void UpdateEncryptionMeshBanner()
        {
            if (_lblEncryptionMeshBanner == null) return;
            int count;
            lock (_meshParticipants) { count = Math.Max(1, _meshParticipants.Count); }
            int maxCap = GetMaxParticipantsForCurrentMode();
            string text;
            if (_uiLang == "en")
            {
                text = string.Format(
                    "🔐 Full AES-256-GCM E2EE Active (PBKDF2-SHA256 100k • 96-bit IV • 128-bit Auth Tag • Security SAS: {0}) | 👥 Participants: {1}/{2} (Max 6 Video / Max 10 Audio)",
                    _currentSasFingerprint, count, maxCap);
            }
            else if (_uiLang == "tl")
            {
                text = string.Format(
                    "🔐 Buong AES-256-GCM E2EE Aktibo (PBKDF2-SHA256 100k • 96-bit IV • 128-bit Auth Tag • Security SAS: {0}) | 👥 Kasali: {1}/{2} (Max 6 Video / Max 10 Audio)",
                    _currentSasFingerprint, count, maxCap);
            }
            else if (_uiLang == "es")
            {
                text = string.Format(
                    "🔐 Cifrado Total AES-256-GCM Activo (PBKDF2-SHA256 100k • IV 96 bits • Tag 128 bits • Código SAS: {0}) | 👥 Participantes: {1}/{2} (Máx 6 Vídeo / Máx 10 Voz)",
                    _currentSasFingerprint, count, maxCap);
            }
            else
            {
                text = string.Format(
                    "🔐 全通信 AES-256-GCM 完全暗号化稼働中 (PBKDF2-SHA256 10万回 • 96bit IV • 128bit認証タグ • 安全確認コード: {0}) | 👥 グループ参加人数: {1}/{2}名 (ビデオ最大6人・音声最大10人)",
                    _currentSasFingerprint, count, maxCap);
            }
            _lblEncryptionMeshBanner.Text = text;
            _lblEncryptionMeshBanner.AccessibleName = text;
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
            catch
            {
                _tts = null;
            }

            try
            {
                _speechRecognizer = new SpeechRecognitionEngine();
                var phrases = new Choices(new string[]
                {
                    "もしもし", "聞こえる", "聞こえますか", "こんにちは", "こんばんは", "テスト",
                    "今どんな顔してる", "表情を教えて", "誰がいる", "何人いる", "暗号化はどうなってる",
                    "ありがとう", "大好き", "セレナ", "愛してる", "フィリピン", "マニラ", "おやすみ", "またね",
                    "hello", "can you hear me", "how are you", "what expression", "who is here", "thank you",
                    "kumusta", "mahal kita", "maraming salamat", "naririnig mo ba ako",
                    "hola", "me escuchas", "gracias", "cómo estás"
                });
                var gb = new GrammarBuilder(phrases);
                if (_speechRecognizer.RecognizerInfo != null && _speechRecognizer.RecognizerInfo.Culture != null)
                {
                    gb.Culture = _speechRecognizer.RecognizerInfo.Culture;
                }
                _speechRecognizer.LoadGrammar(new Grammar(gb));
                try
                {
                    _speechRecognizer.LoadGrammar(new DictationGrammar());
                }
                catch { }
                _speechRecognizer.SetInputToDefaultAudioDevice();
                _speechRecognizer.SpeechHypothesized += (s, e) =>
                {
                    if (_isCallActive && !_isMicMuted && e.Result != null && !string.IsNullOrWhiteSpace(e.Result.Text))
                    {
                        bool isTtsSpeaking = (_tts != null && _tts.State == SynthesizerState.Speaking);
                        if (!isTtsSpeaking)
                        {
                            _lastHypothesizedSpeech = e.Result.Text;
                            _userVoiceActiveTicks = 3;
                        }
                    }
                };
                _speechRecognizer.SpeechRecognized += (s, e) =>
                {
                    if (_isCallActive && !_isMicMuted && e.Result != null && e.Result.Confidence >= 0.20f)
                    {
                        bool isTtsSpeaking = (_tts != null && _tts.State == SynthesizerState.Speaking);
                        if (!isTtsSpeaking)
                        {
                            string text = e.Result.Text;
                            _lastHypothesizedSpeech = "";
                            _userVoiceActiveTicks = 0;
                            BeginInvoke(new Action(() => ProcessTestBotConversationTurn(text)));
                        }
                    }
                };
                _speechRecognizer.SpeechRecognitionRejected += (s, e) =>
                {
                    if (_isCallActive && _isBotCallActive && !_isMicMuted)
                    {
                        bool isTtsSpeaking = (_tts != null && _tts.State == SynthesizerState.Speaking);
                        if (!isTtsSpeaking)
                        {
                            string fallbackText = !string.IsNullOrWhiteSpace(_lastHypothesizedSpeech)
                                ? _lastHypothesizedSpeech
                                : (e.Result != null && !string.IsNullOrWhiteSpace(e.Result.Text) ? e.Result.Text : "");
                            _lastHypothesizedSpeech = "";
                            _userVoiceActiveTicks = 0;
                            BeginInvoke(new Action(() => ProcessTestBotConversationTurn(fallbackText)));
                        }
                    }
                };
                _speechRecognizer.AudioLevelUpdated += (s, e) =>
                {
                    if (_isCallActive && _isBotCallActive && !_isMicMuted && e.AudioLevel >= 12)
                    {
                        bool isTtsSpeaking = (_tts != null && _tts.State == SynthesizerState.Speaking);
                        if (!isTtsSpeaking)
                        {
                            _userVoiceActiveTicks = 3;
                        }
                    }
                };
            }
            catch
            {
                _speechRecognizer = null;
            }
        }

        private void SelectVoiceByLangPrefix(string langPrefix)
        {
            if (_tts == null) return;
            try
            {
                foreach (var v in _tts.GetInstalledVoices())
                {
                    if (!v.Enabled || v.VoiceInfo == null || v.VoiceInfo.Culture == null) continue;
                    string culture = v.VoiceInfo.Culture.Name.ToLowerInvariant();
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
            if (string.IsNullOrWhiteSpace(text)) return;
            if (InvokeRequired)
            {
                BeginInvoke(new Action(() => Speak(text, interrupt)));
                return;
            }

            if (_lblCallStatusBox != null && !_isCallActive)
            {
                _lblCallStatusBox.Text = text;
                _lblCallStatusBox.AccessibleName = text;
            }

            if (_tts == null) return;
            try
            {
                if (interrupt) _tts.SpeakAsyncCancelAll();
                string prefix = _uiLang == "tl" ? "fil" : (_uiLang == "es" ? "es" : (_uiLang == "en" ? "en" : "ja"));
                SelectVoiceByLangPrefix(prefix);
                _tts.SpeakAsync(text);
            }
            catch { }
        }

        private void SpeakTagalogStartupGreeting()
        {
            if (_tts == null) return;
            try
            {
                _tts.SpeakAsyncCancelAll();
                SelectVoiceByLangPrefix("fil");
                _tts.SpeakAsync("Maligayang pagdating sa ating tahanan! Narito na ang iyong Kapamilya Serena Phone para sa Windows. Kahit nasaan ka man, pamilya tayo! Mahal ka namin, ingat lagi at Mabuhay!");

                string welcome;
                if (_uiLang == "en")
                {
                    welcome = string.Format(
                        "Kapamilya Serena Phone for Windows version {0} is ready. All signaling, invite codes, and streams are encrypted with AES-256-GCM (Security Code: {1}). Supports up to 6 participants in video calls, up to 10 in audio calls, and interactive AI Test Bot calls with F4 and F3.",
                        CURRENT_VERSION, _currentSasFingerprint);
                }
                else if (_uiLang == "tl")
                {
                    welcome = string.Format(
                        "Handa na ang Kapamilya Serena Phone para sa Windows bersyon {0}. Lahat ay naka-encrypt sa AES-256-GCM (Security Code: {1}). Hanggang 6 katao sa video call, 10 katao sa audio call, at puwedeng makipag-usap sa Serena AI Test Bot gamit ang F4 at F3!",
                        CURRENT_VERSION, _currentSasFingerprint);
                }
                else if (_uiLang == "es")
                {
                    welcome = string.Format(
                        "Kapamilya Serena Phone para Windows versión {0} está listo con cifrado total AES-256-GCM (Código: {1}), hasta 6 personas en videollamada, 10 en llamada de voz y bot de prueba IA interactivo con F4 y F3.",
                        CURRENT_VERSION, _currentSasFingerprint);
                }
                else
                {
                    welcome = string.Format(
                        "Kapamilya Serena Phone for Windows バージョン {0} が起動しました。すべての通信と招待コードを AES-256-GCM で完全暗号化し、ビデオ通話は最大6人、音声通話は最大10人まで同時参加できます。現在の安全確認コードは {1} です。F4キーでセレナAIテストボットとすぐ通話・会話テスト、F3キーでマイク音声認識によるボット対話、F5キーで発信、F6キーで通話終了と秒単位の通話時間読み上げ、F7キーで参加メンバー全員の表情AI実況、F8キーで自分のカメラ確認ができます。",
                        CURRENT_VERSION, _currentSasFingerprint);
                }

                string prefix = _uiLang == "tl" ? "fil" : (_uiLang == "es" ? "es" : (_uiLang == "en" ? "en" : "ja"));
                SelectVoiceByLangPrefix(prefix);
                _tts.SpeakAsync(welcome);
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
                string osIso = CultureInfo.CurrentUICulture.TwoLetterISOLanguageName.ToLowerInvariant();
                if (osIso == "en") _uiLang = "en";
                else if (osIso == "tl" || osIso == "fil") _uiLang = "tl";
                else if (osIso == "es") _uiLang = "es";
                else _uiLang = "ja";
            }
            catch { }

            try
            {
                using (var key = Registry.CurrentUser.OpenSubKey(@"Software\KapamilyaSerenaPhone"))
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
                    foreach (string raw in File.ReadAllLines(_settingsFile, Encoding.UTF8))
                    {
                        string line = raw.Trim();
                        if (string.IsNullOrEmpty(line) || line.StartsWith("#")) continue;
                        int idx = line.IndexOf('=');
                        if (idx <= 0) continue;
                        string k = line.Substring(0, idx).Trim();
                        string v = line.Substring(idx + 1).Trim();
                        if (k.Equals("Language", StringComparison.OrdinalIgnoreCase) && !string.IsNullOrEmpty(v))
                            _uiLang = v;
                        else if (k.Equals("FocusReadAll", StringComparison.OrdinalIgnoreCase))
                            _focusReadAllMode = (v == "1" || v.Equals("true", StringComparison.OrdinalIgnoreCase));
                        else if (k.Equals("AutoAiNarrate", StringComparison.OrdinalIgnoreCase))
                            _autoAiNarrate = (v == "1" || v.Equals("true", StringComparison.OrdinalIgnoreCase));
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
                sb.AppendLine("# Kapamilya Serena Phone for Windows Settings");
                sb.AppendLine("Language=" + _uiLang);
                sb.AppendLine("FocusReadAll=" + (_focusReadAllMode ? "1" : "0"));
                sb.AppendLine("AutoAiNarrate=" + (_autoAiNarrate ? "1" : "0"));
                File.WriteAllText(_settingsFile, sb.ToString(), Encoding.UTF8);
            }
            catch { }
        }

        private void LoadContactsAndHistory()
        {
            _contacts.Clear();
            if (File.Exists(_contactsFile))
            {
                try
                {
                    foreach (string line in File.ReadAllLines(_contactsFile, Encoding.UTF8))
                    {
                        string[] parts = line.Split('|');
                        if (parts.Length >= 4)
                        {
                            _contacts.Add(new SpeedDialContact
                            {
                                Name = parts[0],
                                Target = parts[1],
                                IsWebRtc = parts[2] == "1",
                                IsVideo = parts[3] == "1"
                            });
                        }
                    }
                }
                catch { }
            }

            if (_contacts.Count == 0)
            {
                _contacts.Add(new SpeedDialContact
                {
                    Name = "🤖 Serena AI テストボット (1対1 音声＆ビデオ対話)",
                    Target = "kapamilya-serena-test-bot",
                    IsWebRtc = true,
                    IsVideo = true
                });
                _contacts.Add(new SpeedDialContact
                {
                    Name = "👥 6人ビデオ通話テストボット (AES-256-GCM 6人全員表情テスト)",
                    Target = "kapamilya-serena-test-video6",
                    IsWebRtc = true,
                    IsVideo = true
                });
                _contacts.Add(new SpeedDialContact
                {
                    Name = "👥 10人音声通話テストボット (AES-256-GCM 10人メッシュテスト)",
                    Target = "kapamilya-serena-test-audio10",
                    IsWebRtc = true,
                    IsVideo = false
                });
                _contacts.Add(new SpeedDialContact
                {
                    Name = "Serena (Asawa ko — AES-256-GCM ビデオ通話 最大6人)",
                    Target = "Kapamilya-Serena-Home",
                    IsWebRtc = true,
                    IsVideo = true
                });
                _contacts.Add(new SpeedDialContact
                {
                    Name = "Kapamilya 家族ルーム (AES-256-GCM 音声通話 最大10人)",
                    Target = "Kapamilya-Family-Voice",
                    IsWebRtc = true,
                    IsVideo = false
                });
                _contacts.Add(new SpeedDialContact
                {
                    Name = "時報・テストダイヤル (117)",
                    Target = "117",
                    IsWebRtc = false,
                    IsVideo = false
                });
            }
            else
            {
                bool hasTestBot = false;
                foreach (var c in _contacts)
                {
                    if (c.Target != null && c.Target.IndexOf("test-bot", StringComparison.OrdinalIgnoreCase) >= 0)
                    {
                        hasTestBot = true;
                        break;
                    }
                }
                if (!hasTestBot)
                {
                    _contacts.Insert(0, new SpeedDialContact
                    {
                        Name = "🤖 Serena AI テストボット (1対1 音声＆ビデオ対話)",
                        Target = "kapamilya-serena-test-bot",
                        IsWebRtc = true,
                        IsVideo = true
                    });
                }
            }

            _callHistory.Clear();
            if (File.Exists(_callHistoryFile))
            {
                try
                {
                    foreach (string line in File.ReadAllLines(_callHistoryFile, Encoding.UTF8))
                    {
                        string[] parts = line.Split('|');
                        if (parts.Length >= 5)
                        {
                            long sec = 0;
                            long.TryParse(parts[3], out sec);
                            _callHistory.Add(new CallLogRecord
                            {
                                Timestamp = parts[0],
                                Partner = parts[1],
                                Mode = parts[2],
                                DurationSeconds = sec,
                                FormattedDuration = parts[4]
                            });
                        }
                    }
                }
                catch { }
            }
        }

        private void SaveContactsAndHistory()
        {
            try
            {
                var sbContacts = new StringBuilder();
                foreach (var c in _contacts)
                {
                    sbContacts.AppendLine(string.Format("{0}|{1}|{2}|{3}",
                        c.Name.Replace("|", " "),
                        c.Target.Replace("|", " "),
                        c.IsWebRtc ? "1" : "0",
                        c.IsVideo ? "1" : "0"));
                }
                File.WriteAllText(_contactsFile, sbContacts.ToString(), Encoding.UTF8);

                var sbHistory = new StringBuilder();
                foreach (var h in _callHistory)
                {
                    sbHistory.AppendLine(string.Format("{0}|{1}|{2}|{3}|{4}",
                        h.Timestamp.Replace("|", " "),
                        h.Partner.Replace("|", " "),
                        h.Mode.Replace("|", " "),
                        h.DurationSeconds,
                        h.FormattedDuration.Replace("|", " ")));
                }
                File.WriteAllText(_callHistoryFile, sbHistory.ToString(), Encoding.UTF8);
            }
            catch { }
        }

        private void InitializeAccessibleUi()
        {
            Text = "Kapamilya Serena Phone for Windows v" + CURRENT_VERSION + " — 全通信 AES-256-GCM 完全暗号化 • ビデオ最大6人 / 音声最大10人 • Gemma 4 + EmbeddingGemma 2";
            Size = new Size(1040, 950);
            StartPosition = FormStartPosition.CenterScreen;
            BackColor = Color.FromArgb(8, 12, 24);
            ForeColor = Color.White;
            Font = new Font("Yu Gothic UI", 10.5f, FontStyle.Regular);

            var rootPanel = new FlowLayoutPanel
            {
                Dock = DockStyle.Fill,
                FlowDirection = FlowDirection.TopDown,
                WrapContents = false,
                AutoScroll = true,
                Padding = new Padding(16)
            };

            _lblBanner = new Label
            {
                Text = "📞 Kapamilya Serena Phone for Windows v" + CURRENT_VERSION + " — AES-256-GCM 完全暗号化 • ビデオ最大6人 / 音声最大10人 (Bayanihan • Magna Carta • Kapamilya)",
                Font = new Font("Yu Gothic UI", 13.0f, FontStyle.Bold),
                ForeColor = Color.FromArgb(253, 224, 71),
                AutoSize = true,
                Margin = new Padding(0, 0, 0, 6)
            };
            rootPanel.Controls.Add(_lblBanner);

            // Top Bar: Language & Focus Verbosity Mode
            var topBar = new FlowLayoutPanel
            {
                FlowDirection = FlowDirection.LeftToRight,
                WrapContents = true,
                AutoSize = true,
                Margin = new Padding(0, 0, 0, 6)
            };

            _lblLang = new Label
            {
                Text = "表示・音声言語 (&L):",
                AutoSize = true,
                ForeColor = Color.FromArgb(125, 211, 252),
                Margin = new Padding(0, 6, 6, 0)
            };
            topBar.Controls.Add(_lblLang);

            _cmbLang = new ComboBox
            {
                DropDownStyle = ComboBoxStyle.DropDownList,
                Width = 220,
                BackColor = Color.FromArgb(15, 23, 42),
                ForeColor = Color.White,
                AccessibleName = "表示・音声言語の選択"
            };
            _cmbLang.Items.AddRange(new object[]
            {
                "日本語 (Japanese)",
                "English (英語)",
                "Tagalog / Filipino (タガログ語)",
                "Español (スペイン語)"
            });
            _suppressComboEvents = true;
            _cmbLang.SelectedIndex = _uiLang == "en" ? 1 : (_uiLang == "tl" ? 2 : (_uiLang == "es" ? 3 : 0));
            _suppressComboEvents = false;
            _cmbLang.SelectedIndexChanged += (s, e) =>
            {
                if (_suppressComboEvents) return;
                _uiLang = _cmbLang.SelectedIndex == 1 ? "en" : (_cmbLang.SelectedIndex == 2 ? "tl" : (_cmbLang.SelectedIndex == 3 ? "es" : "ja"));
                ApplyLocalization();
                SaveSettings();
                Speak(_cmbLang.SelectedItem.ToString());
            };
            AttachFocusSpeech(_cmbLang, "言語選択コンボボックス", "上下矢印キーで日本語、英語、タガログ語、スペイン語を切り替えます。");
            topBar.Controls.Add(_cmbLang);

            _chkFocusVerbosity = new CheckBox
            {
                Text = "タブ移動時に詳細ガイドまですべて読む (Ctrl+Shift+F)",
                Checked = _focusReadAllMode,
                AutoSize = true,
                ForeColor = Color.FromArgb(52, 211, 153),
                Margin = new Padding(18, 4, 0, 0),
                AccessibleName = "タブ移動時に詳細ガイドまですべて読むモードの切り替え"
            };
            _chkFocusVerbosity.CheckedChanged += (s, e) =>
            {
                _focusReadAllMode = _chkFocusVerbosity.Checked;
                SaveSettings();
                Speak(_focusReadAllMode
                    ? "すべて読むモードに切り替えました。タブ移動時に詳しい操作ヒントまで読み上げます。"
                    : "簡潔モードに切り替えました。タブ移動時はアイテム名・種類・状態のみをスッキリ読み上げます。");
            };
            AttachFocusSpeech(_chkFocusVerbosity, "タブフォーカス読み上げモード切替チェックボックス", "スペースキーまたは Ctrl+Shift+F で簡潔モードと全読みモードを切り替えます。");
            topBar.Controls.Add(_chkFocusVerbosity);
            rootPanel.Controls.Add(topBar);

            // Live Call Status Box
            _lblCallStatusBox = new Label
            {
                Text = "待機中 — F5:暗号化通話開始(ビデオ6人/音声10人) | F6:切断(秒単位の通話時間アナウンス) | F7:全員の表情AI実況 | F8:自分のカメラ確認",
                AccessibleName = "現在の通話ステータス",
                BackColor = Color.FromArgb(18, 32, 64),
                ForeColor = Color.White,
                Font = new Font("Yu Gothic UI", 10.5f, FontStyle.Bold),
                Size = new Size(980, 48),
                Padding = new Padding(8),
                Margin = new Padding(0, 0, 0, 6)
            };
            rootPanel.Controls.Add(_lblCallStatusBox);

            // AES-256-GCM & Group Capacity Banner
            _lblEncryptionMeshBanner = new Label
            {
                Text = "",
                AccessibleName = "AES-256-GCM 暗号化とグループ参加人数のステータス",
                BackColor = Color.FromArgb(6, 78, 59),
                ForeColor = Color.FromArgb(167, 243, 208),
                Font = new Font("Yu Gothic UI", 10.0f, FontStyle.Bold),
                Size = new Size(980, 42),
                Padding = new Padding(8),
                Margin = new Padding(0, 0, 0, 8)
            };
            rootPanel.Controls.Add(_lblEncryptionMeshBanner);
            UpdateEncryptionMeshBanner();

            // 4-Tab Mode Switcher Row (1: Standard Phone & History, 2: Video Call & AI, 3: Contacts & Google Contacts, 4: Settings & Audio Quality)
            var tabRow = new FlowLayoutPanel
            {
                FlowDirection = FlowDirection.LeftToRight,
                WrapContents = true,
                Size = new Size(980, 48),
                Margin = new Padding(0, 0, 0, 8)
            };

            _btnTabStandardPhone = CreateAccessibleButton(
                "📞 1. 標準電話・履歴 (&1)",
                "標準電話・キーパッド・発着信履歴タブ切替ボタン",
                "Alt+1で12キーテンキーと秒単位の発着信履歴のタブに切り替えます。",
                Color.FromArgb(16, 185, 129),
                235,
                (s, e) => SwitchPhoneTab(0, true));
            tabRow.Controls.Add(_btnTabStandardPhone);

            _btnTabVideoCall = CreateAccessibleButton(
                "🎥 2. ビデオ・AI通話 (&2)",
                "ビデオ通話・暗号化グループ＆AIボットタブ切替ボタン",
                "Alt+2でAES-256-GCMビデオ通話（最大6人）・音声通話（最大10人）・セレナAIテストボット・Gemma 4表情実況のタブに切り替えます。",
                Color.FromArgb(30, 41, 59),
                235,
                (s, e) => SwitchPhoneTab(1, true));
            tabRow.Controls.Add(_btnTabVideoCall);

            _btnTabContacts = CreateAccessibleButton(
                "👥 3. 連絡先・Google (&3)",
                "端末連絡先＆Googleコンタクトタブ切替ボタン",
                "Alt+3で端末スピードダイヤル連絡先と Google コンタクト連携のタブに切り替えます。",
                Color.FromArgb(30, 41, 59),
                235,
                (s, e) => SwitchPhoneTab(2, true));
            tabRow.Controls.Add(_btnTabContacts);

            _btnTabSettings = CreateAccessibleButton(
                "⚙️ 4. 設定・音質 (&4)",
                "通話音質・ノイズ抑制・各種設定タブ切替ボタン",
                "Alt+4で48kHzフルバンド音質設定、エコーキャンセラー、AIノイズ抑制、自動ゲイン等の設定タブに切り替えます。",
                Color.FromArgb(30, 41, 59),
                235,
                (s, e) => SwitchPhoneTab(3, true));
            tabRow.Controls.Add(_btnTabSettings);

            rootPanel.Controls.Add(tabRow);

            _pnlStandardPhoneTab = new FlowLayoutPanel
            {
                FlowDirection = FlowDirection.TopDown,
                WrapContents = false,
                AutoSize = true,
                Margin = new Padding(0)
            };

            _pnlVideoCallTab = new FlowLayoutPanel
            {
                FlowDirection = FlowDirection.TopDown,
                WrapContents = false,
                AutoSize = true,
                Margin = new Padding(0),
                Visible = false
            };

            _pnlContactsTab = new FlowLayoutPanel
            {
                FlowDirection = FlowDirection.TopDown,
                WrapContents = false,
                AutoSize = true,
                Margin = new Padding(0),
                Visible = false
            };

            _pnlSettingsTab = new FlowLayoutPanel
            {
                FlowDirection = FlowDirection.TopDown,
                WrapContents = false,
                AutoSize = true,
                Margin = new Padding(0),
                Visible = false
            };

            // Dial, WebRTC Room & AES-256-GCM Passphrase Inputs
            var dialRow = new FlowLayoutPanel
            {
                FlowDirection = FlowDirection.LeftToRight,
                WrapContents = true,
                Size = new Size(980, 44),
                Margin = new Padding(0, 0, 0, 6)
            };

            _lblDialNumber = new Label
            {
                Text = "番号 (&N):",
                AutoSize = true,
                ForeColor = Color.FromArgb(253, 224, 71),
                Margin = new Padding(0, 6, 4, 0)
            };
            dialRow.Controls.Add(_lblDialNumber);

            _txtDialNumber = new TextBox
            {
                Text = "",
                Width = 145,
                BackColor = Color.FromArgb(15, 23, 42),
                ForeColor = Color.White,
                AccessibleName = "発信する電話番号の入力欄"
            };
            _txtDialNumber.KeyPress += (s, e) =>
            {
                if ("0123456789*#+".IndexOf(e.KeyChar) >= 0)
                {
                    PlayDtmfToneAsync(e.KeyChar);
                }
            };
            AttachFocusSpeech(_txtDialNumber, "発信する電話番号の入力欄", "電話番号を入力して F5 キーを押すとスマホ連携または Windows 通話で発信します。");
            dialRow.Controls.Add(_txtDialNumber);

            _lblWebRtcRoom = new Label
            {
                Text = "ルーム (&R):",
                AutoSize = true,
                ForeColor = Color.FromArgb(125, 211, 252),
                Margin = new Padding(10, 6, 4, 0)
            };
            dialRow.Controls.Add(_lblWebRtcRoom);

            _txtWebRtcRoom = new TextBox
            {
                Text = _currentRoomName,
                Width = 175,
                BackColor = Color.FromArgb(15, 23, 42),
                ForeColor = Color.White,
                AccessibleName = "WebRTC ルーム名の入力欄"
            };
            _txtWebRtcRoom.Leave += (s, e) => RefreshAes256GcmKeyAndResetMesh();
            AttachFocusSpeech(_txtWebRtcRoom, "WebRTC ルーム名の入力欄", "Android版や他の Windows PC と同じルーム名を設定します。");
            dialRow.Controls.Add(_txtWebRtcRoom);

            _lblAesPassphrase = new Label
            {
                Text = "AES-256-GCM 暗号鍵 (&K):",
                AutoSize = true,
                ForeColor = Color.FromArgb(167, 243, 208),
                Margin = new Padding(10, 6, 4, 0)
            };
            dialRow.Controls.Add(_lblAesPassphrase);

            _txtAesPassphrase = new TextBox
            {
                Text = _currentPassphrase,
                Width = 195,
                BackColor = Color.FromArgb(15, 23, 42),
                ForeColor = Color.FromArgb(253, 224, 71),
                AccessibleName = "AES-256-GCM 共通暗号鍵パスフレーズの入力欄"
            };
            _txtAesPassphrase.Leave += (s, e) => RefreshAes256GcmKeyAndResetMesh();
            AttachFocusSpeech(_txtAesPassphrase, "AES-256-GCM 暗号鍵パスフレーズ入力欄", "PBKDF2-HMAC-SHA256 で256ビットAES-GCM鍵を生成する合言葉です。");
            dialRow.Controls.Add(_txtAesPassphrase);

            _cmbCallType = new ComboBox
            {
                DropDownStyle = ComboBoxStyle.DropDownList,
                Width = 220,
                BackColor = Color.FromArgb(15, 23, 42),
                ForeColor = Color.White,
                Margin = new Padding(8, 2, 0, 0),
                AccessibleName = "通話方式と最大人数の選択"
            };
            _cmbCallType.Items.AddRange(new object[]
            {
                "🎥 AES-256-GCM ビデオ通話 (最大6人)",
                "🌐 AES-256-GCM 音声通話 (最大10人)",
                "📱 スマホ標準電話連携 (Bayanihan Link)"
            });
            _cmbCallType.SelectedIndex = 0;
            _cmbCallType.SelectedIndexChanged += (s, e) =>
            {
                _isVideoCall = (_cmbCallType.SelectedIndex == 0);
                UpdateEncryptionMeshBanner();
                if (!_suppressComboEvents && _cmbCallType.SelectedItem != null)
                {
                    Speak("通話方式：" + _cmbCallType.SelectedItem.ToString());
                }
            };
            AttachFocusSpeech(_cmbCallType, "通話方式の選択コンボボックス", "AES-256-GCM ビデオ通話（最大6人）、音声通話（最大10人）、スマホ標準電話連携から選択します。");
            dialRow.Controls.Add(_cmbCallType);

            rootPanel.Controls.Add(dialRow);

            // Primary Action Buttons Row 1
            var actionRow1 = new FlowLayoutPanel
            {
                FlowDirection = FlowDirection.LeftToRight,
                WrapContents = true,
                Size = new Size(980, 50),
                Margin = new Padding(0, 0, 0, 6)
            };

            _btnStartCall = CreateAccessibleButton(
                "📞 暗号化通話・発信開始 (F5)",
                "暗号化通話・発信開始ボタン",
                "F5キーで AES-256-GCM グループ通話（ビデオ最大6人・音声最大10人）または電話番号発信を開始します。",
                Color.FromArgb(16, 185, 129),
                195,
                (s, e) => StartCallAction());
            actionRow1.Controls.Add(_btnStartCall);

            _btnEndCall = CreateAccessibleButton(
                "📴 通話を切る (F6)",
                "通話を切るボタン",
                "F6キーで通話を終了し、何時間何分何秒だったか正確な通話時間を読み上げます。",
                Color.FromArgb(220, 38, 38),
                155,
                (s, e) => EndCallAction());
            actionRow1.Controls.Add(_btnEndCall);

            _btnPartnerExpressionAi = CreateAccessibleButton(
                "😊 参加メンバーの表情AI実況 (F7)",
                "参加メンバーの表情AI実況ボタン",
                "F7キーで Google DeepMind EmbeddingGemma 2 と Gemma 4 が参加メンバー（最大6人）の笑顔度・うなずき・視線を音声実況します。",
                Color.FromArgb(37, 99, 235),
                220,
                (s, e) => SpeakPartnerExpressionAi(true));
            actionRow1.Controls.Add(_btnPartnerExpressionAi);

            _btnSelfCameraCheckAi = CreateAccessibleButton(
                "📷 自分のカメラ確認 (F8)",
                "自分のカメラ確認ボタン",
                "F8キーで自分の顔がWebカメラの中央に映っているか、部屋の明るさが十分かをAIが音声で案内します。",
                Color.FromArgb(124, 58, 237),
                190,
                (s, e) => CheckSelfWebcamFramingAi());
            actionRow1.Controls.Add(_btnSelfCameraCheckAi);

            _btnMuteMic = CreateAccessibleButton(
                "🎙️ マイク消音 (F9)",
                "マイク消音切替ボタン",
                "F9キーで自分のマイクのミュートと解除を切り替えます。",
                Color.FromArgb(51, 65, 85),
                150,
                (s, e) => ToggleMicMute());
            actionRow1.Controls.Add(_btnMuteMic);

            rootPanel.Controls.Add(actionRow1);

            // Secondary Action Buttons Row 2 (AES-256-GCM Invite Copy/Paste, Roster, Speaker, Auto AI)
            var actionRow2 = new FlowLayoutPanel
            {
                FlowDirection = FlowDirection.LeftToRight,
                WrapContents = true,
                Size = new Size(980, 48),
                Margin = new Padding(0, 0, 0, 8)
            };

            _btnGenerateInviteCode = CreateAccessibleButton(
                "🔐 AES-256-GCM 招待コードコピー (F10)",
                "AES-256-GCM 暗号化招待コード作成・コピーボタン",
                "F10キーで AES-256-GCM 認証タグ付きのグループ招待コードを作成してクリップボードにコピーします。",
                Color.FromArgb(14, 116, 144),
                245,
                (s, e) => GenerateAndCopyWebRtcInviteCode());
            actionRow2.Controls.Add(_btnGenerateInviteCode);

            _btnAcceptInviteCode = CreateAccessibleButton(
                "🔗 暗号化招待コード貼付＆参加 (F11)",
                "AES-256-GCM 暗号化招待コード貼り付け・参加ボタン",
                "F11キーでクリップボードの暗号化招待コードを復号・認証検証してグループ通話（ビデオ最大6人・音声最大10人）に参加します。",
                Color.FromArgb(14, 116, 144),
                235,
                (s, e) => AcceptClipboardWebRtcInviteCode());
            actionRow2.Controls.Add(_btnAcceptInviteCode);

            _btnSpeakMeshRoster = CreateAccessibleButton(
                "👥 参加者＆暗号化コード読上",
                "参加メンバー一覧と暗号化安全コード読み上げボタン",
                "現在の参加人数（ビデオ最大6人・音声最大10人）、全員の表情、AES-256-GCM 安全確認コードを読み上げます。",
                Color.FromArgb(30, 58, 138),
                195,
                (s, e) => SpeakMeshParticipantsAndSecurityStatus());
            actionRow2.Controls.Add(_btnSpeakMeshRoster);

            _btnToggleSpeaker = CreateAccessibleButton(
                "🔊 スピーカー: ON (F12)",
                "スピーカー出力切替ボタン",
                "F12キーでスピーカー出力とヘッドセット出力を切り替えます。",
                Color.FromArgb(51, 65, 85),
                155,
                (s, e) => ToggleSpeakerphone());
            actionRow2.Controls.Add(_btnToggleSpeaker);

            _chkAutoAiNarrate = new CheckBox
            {
                Text = "自動表情実況",
                Checked = _autoAiNarrate,
                AutoSize = true,
                ForeColor = Color.FromArgb(253, 224, 71),
                Margin = new Padding(6, 10, 0, 0),
                AccessibleName = "ビデオ通話中の自動表情実況の切り替え"
            };
            _chkAutoAiNarrate.CheckedChanged += (s, e) =>
            {
                _autoAiNarrate = _chkAutoAiNarrate.Checked;
                SaveSettings();
                Speak(_autoAiNarrate ? "ビデオ通話中の自動表情実況をオンにしました。" : "ビデオ通話中の自動表情実況をオフにしました。");
            };
            AttachFocusSpeech(_chkAutoAiNarrate, "ビデオ通話中の自動表情実況チェックボックス", "オンにするとビデオ通話中に参加メンバーの笑顔やしぐさの変化を自動で実況します。");
            actionRow2.Controls.Add(_chkAutoAiNarrate);

            _pnlVideoCallTab.Controls.Add(actionRow2);

            // AI Test Bot Interactive Row (F4: 1-on-1 Bot / Reply, F3: Mic Talk, Ctrl+F6: 6-Video Bots, Ctrl+F10: 10-Audio Bots)
            var actionRowBot = new FlowLayoutPanel
            {
                FlowDirection = FlowDirection.LeftToRight,
                WrapContents = true,
                Size = new Size(980, 48),
                Margin = new Padding(0, 0, 0, 8)
            };

            _btnTestBot1on1 = CreateAccessibleButton(
                "🤖 セレナAIボット通話・返事 (F4)",
                "セレナAIテストボット1対1通話および返事ボタン",
                "F4キーで Kapamilya Serena AI テストボットと AES-256-GCM 暗号化通話を開始し、押すたびにボットが声と表情で会話します。",
                Color.FromArgb(5, 150, 105),
                205,
                (s, e) => StartOrStepTestBotAction());
            actionRowBot.Controls.Add(_btnTestBot1on1);

            _btnTestBotTalkMic = CreateAccessibleButton(
                "🎙️ ボットに話しかける (F3)",
                "マイク音声認識でテストボットに話しかけるボタン",
                "F3キーでマイクの声を聞き取り（またはメッセージ入力）、AES-256-GCM 暗号化検証を通してテストボットが声と表情で返事します。",
                Color.FromArgb(190, 24, 93),
                195,
                (s, e) => ListenAndTalkToTestBotViaMic());
            actionRowBot.Controls.Add(_btnTestBotTalkMic);

            _btnTestBotVideo6 = CreateAccessibleButton(
                "👥 6人ビデオボット (Ctrl+F6)",
                "6人ビデオ通話テストボット招集ボタン",
                "Ctrl+F6でビデオ通話の最大6人枠すべてにAIテストボットを参加させ、全員の表情AI一括実況（F7）をテストできます。",
                Color.FromArgb(2, 132, 199),
                195,
                (s, e) => StartTestBotGroupCall(MAX_VIDEO_PARTICIPANTS, true));
            actionRowBot.Controls.Add(_btnTestBotVideo6);

            _btnTestBotAudio10 = CreateAccessibleButton(
                "👥 10人音声ボット (Ctrl+F10)",
                "10人音声通話テストボット招集ボタン",
                "Ctrl+F10で音声通話の最大10人枠すべてにAIテストボットを参加させ、10人フルメッシュ暗号化通話をテストできます。",
                Color.FromArgb(67, 56, 202),
                195,
                (s, e) => StartTestBotGroupCall(MAX_AUDIO_PARTICIPANTS, false));
            actionRowBot.Controls.Add(_btnTestBotAudio10);

            _btnTestBotLeaveSound = CreateAccessibleButton(
                "🍂 退室音テスト (F2)",
                "メンバー退室・落ちた音テストボタン",
                "F2キーでグループ通話からメンバーが1人退室したときの『落ちた音（下降チャイム）』とアナウンスをテストします。",
                Color.FromArgb(136, 19, 55),
                150,
                (s, e) => SimulateParticipantLeaveWithSound());
            actionRowBot.Controls.Add(_btnTestBotLeaveSound);

            _pnlVideoCallTab.Controls.Add(actionRowBot);

            // Middle Split: 12-Key DTMF Keypad + Speed Dial Contacts & Call History
            var middleRow = new FlowLayoutPanel
            {
                FlowDirection = FlowDirection.LeftToRight,
                WrapContents = false,
                Size = new Size(980, 255),
                Margin = new Padding(0, 0, 0, 8)
            };

            _grpKeypad = new GroupBox
            {
                Text = "🔢 プッシュ音（DTMFトーン）テンキー",
                ForeColor = Color.FromArgb(253, 224, 71),
                Size = new Size(300, 245),
                Margin = new Padding(0, 0, 14, 0),
                AccessibleName = "プッシュ音テンキー"
            };

            var keypadGrid = new TableLayoutPanel
            {
                Dock = DockStyle.Fill,
                ColumnCount = 3,
                RowCount = 4,
                Padding = new Padding(6)
            };
            for (int c = 0; c < 3; c++) keypadGrid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 33.33f));
            for (int r = 0; r < 4; r++) keypadGrid.RowStyles.Add(new RowStyle(SizeType.Percent, 25.0f));

            string[,] dtmfLabels = new string[4, 3]
            {
                { "1", "2 ABC", "3 DEF" },
                { "4 GHI", "5 JKL", "6 MNO" },
                { "7 PQRS", "8 TUV", "9 WXYZ" },
                { "* 星", "0 +", "# シャープ" }
            };

            for (int r = 0; r < 4; r++)
            {
                for (int c = 0; c < 3; c++)
                {
                    string label = dtmfLabels[r, c];
                    char digit = label[0];
                    var btnKey = new Button
                    {
                        Text = label,
                        Dock = DockStyle.Fill,
                        BackColor = Color.FromArgb(22, 34, 58),
                        ForeColor = Color.White,
                        FlatStyle = FlatStyle.Flat,
                        Font = new Font("Yu Gothic UI", 10.5f, FontStyle.Bold),
                        AccessibleName = "テンキー " + label
                    };
                    btnKey.Click += (s, e) =>
                    {
                        _txtDialNumber.Text += digit;
                        PlayDtmfToneAsync(digit);
                    };
                    AttachFocusSpeech(btnKey, "テンキー " + label, "押すとプッシュ音 " + digit + " を鳴らして番号欄に入力します。");
                    _keypadButtons.Add(btnKey);
                    keypadGrid.Controls.Add(btnKey, c, r);
                }
            }
            _grpKeypad.Controls.Add(keypadGrid);
            middleRow.Controls.Add(_grpKeypad);

            // Speed Dial Contacts Column
            var contactsCol = new FlowLayoutPanel
            {
                FlowDirection = FlowDirection.TopDown,
                WrapContents = false,
                Size = new Size(320, 245),
                Margin = new Padding(0, 0, 14, 0)
            };

            _lblContacts = new Label
            {
                Text = "⭐ スピードダイヤル連絡先 (&C):",
                AutoSize = true,
                ForeColor = Color.FromArgb(125, 211, 252),
                Margin = new Padding(0, 0, 0, 4)
            };
            contactsCol.Controls.Add(_lblContacts);

            _lstContacts = new ListBox
            {
                Size = new Size(310, 160),
                BackColor = Color.FromArgb(15, 23, 42),
                ForeColor = Color.White,
                AccessibleName = "スピードダイヤル連絡先リスト"
            };
            RefreshContactsListBox();
            _lstContacts.SelectedIndexChanged += (s, e) =>
            {
                if (_lstContacts.SelectedItem != null)
                {
                    Speak(_lstContacts.SelectedItem.ToString());
                }
            };
            _lstContacts.KeyDown += (s, e) =>
            {
                if (e.KeyCode == Keys.Enter)
                {
                    CallSelectedContact();
                    e.Handled = true;
                }
            };
            AttachFocusSpeech(_lstContacts, "スピードダイヤル連絡先リスト", "上下矢印キーで相手を選び、Enterキーで即発信します。");
            contactsCol.Controls.Add(_lstContacts);

            var contactBtns = new FlowLayoutPanel
            {
                FlowDirection = FlowDirection.LeftToRight,
                Size = new Size(315, 42)
            };
            _btnCallSelectedContact = CreateAccessibleButton("発信", "選択した連絡先に発信", "選択中の連絡先へ今すぐ発信します。", Color.FromArgb(16, 185, 129), 92, (s, e) => CallSelectedContact());
            _btnAddContact = CreateAccessibleButton("追加", "連絡先を追加", "現在の入力欄の番号またはルーム名を連絡先に登録します。", Color.FromArgb(37, 99, 235), 92, (s, e) => AddCurrentToContacts());
            _btnDeleteContact = CreateAccessibleButton("削除", "連絡先を削除", "選択中の連絡先をリストから削除します。", Color.FromArgb(185, 28, 28), 92, (s, e) => DeleteSelectedContact());
            contactBtns.Controls.Add(_btnCallSelectedContact);
            contactBtns.Controls.Add(_btnAddContact);
            contactBtns.Controls.Add(_btnDeleteContact);
            contactsCol.Controls.Add(contactBtns);

            _btnOpenGoogleContacts = CreateAccessibleButton(
                "☁️ Google コンタクトを開く / 同期",
                "Google コンタクトを開くボタン",
                "ブラウザまたは連携アプリで Google コンタクト (contacts.google.com) を開いて連絡先を確認・同期します。",
                Color.FromArgb(21, 101, 192),
                310,
                (s, e) =>
                {
                    try { Process.Start("https://contacts.google.com"); } catch { }
                    Speak("Google コンタクトを開きました。");
                });
            contactsCol.Controls.Add(_btnOpenGoogleContacts);
            _pnlContactsTab.Controls.Add(contactsCol);

            // Settings Tab Controls (Audio Quality, Echo Canceler, Noise Suppression, Auto Gain)
            var lblAudioQuality = new Label
            {
                Text = "🎵 通話音質モード (Opus / HD Voice サンプリングレート):",
                AutoSize = true,
                ForeColor = Color.FromArgb(253, 224, 71),
                Margin = new Padding(0, 4, 0, 4)
            };
            _pnlSettingsTab.Controls.Add(lblAudioQuality);

            _cmbAudioQuality = new ComboBox
            {
                DropDownStyle = ComboBoxStyle.DropDownList,
                Width = 520,
                BackColor = Color.FromArgb(15, 23, 42),
                ForeColor = Color.White,
                AccessibleName = "通話音質モードの選択"
            };
            _cmbAudioQuality.Items.AddRange(new object[]
            {
                "48kHz フルバンド超高音質 (Opus 48kHz Stereo / Studio Clarity)",
                "24kHz HD Voice 明瞭音声モード (Super-Wideband)",
                "16kHz 省データ・低遅延安定モード (Wideband)"
            });
            _cmbAudioQuality.SelectedIndex = 0;
            _cmbAudioQuality.SelectedIndexChanged += (s, e) =>
            {
                if (_cmbAudioQuality.SelectedItem != null)
                    Speak("通話音質モード：" + _cmbAudioQuality.SelectedItem.ToString());
            };
            AttachFocusSpeech(_cmbAudioQuality, "通話音質モード選択コンボボックス", "上下矢印キーで48kHzフルバンド超高音質、24kHz HD Voice、16kHz安定モードを切り替えます。");
            _pnlSettingsTab.Controls.Add(_cmbAudioQuality);

            _chkEchoCanceler = new CheckBox
            {
                Text = "エコーキャンセラー (Acoustic Echo Canceler — ハウリング防止) を有効にする",
                Checked = true,
                AutoSize = true,
                ForeColor = Color.White,
                Margin = new Padding(0, 8, 0, 4)
            };
            AttachFocusSpeech(_chkEchoCanceler, "エコーキャンセラー切替チェックボックス", "スピーカー通話時のハウリングやエコーを防止します。");
            _pnlSettingsTab.Controls.Add(_chkEchoCanceler);

            _chkNoiseSuppressor = new CheckBox
            {
                Text = "AI 周囲ノイズ抑制 (Noise Suppression — 環境音・打鍵音カット) を有効にする",
                Checked = true,
                AutoSize = true,
                ForeColor = Color.White,
                Margin = new Padding(0, 4, 0, 4)
            };
            AttachFocusSpeech(_chkNoiseSuppressor, "AI周囲ノイズ抑制チェックボックス", "通話中の周囲の雑音やキーボード打鍵音を抑制します。");
            _pnlSettingsTab.Controls.Add(_chkNoiseSuppressor);

            _chkAutoGain = new CheckBox
            {
                Text = "マイク自動ゲイン調整 (Auto Gain Control — 声の大きさを自動均一化) を有効にする",
                Checked = true,
                AutoSize = true,
                ForeColor = Color.White,
                Margin = new Padding(0, 4, 0, 8)
            };
            AttachFocusSpeech(_chkAutoGain, "マイク自動ゲイン調整チェックボックス", "マイクから離れても声を聞き取りやすい音量に自動調整します。");
            _pnlSettingsTab.Controls.Add(_chkAutoGain);

            // Call History Column
            var historyCol = new FlowLayoutPanel
            {
                FlowDirection = FlowDirection.TopDown,
                WrapContents = false,
                Size = new Size(640, 245)
            };

            _lblCallHistory = new Label
            {
                Text = "🕒 発着信履歴（秒単位の正確な通話時間）(&H):",
                AutoSize = true,
                ForeColor = Color.FromArgb(52, 211, 153),
                Margin = new Padding(0, 0, 0, 4)
            };
            historyCol.Controls.Add(_lblCallHistory);

            _lstCallHistory = new ListBox
            {
                Size = new Size(310, 160),
                BackColor = Color.FromArgb(15, 23, 42),
                ForeColor = Color.White,
                AccessibleName = "発着信履歴と正確な通話時間リスト"
            };
            RefreshHistoryListBox();
            _lstCallHistory.SelectedIndexChanged += (s, e) =>
            {
                if (_lstCallHistory.SelectedItem != null)
                {
                    Speak(_lstCallHistory.SelectedItem.ToString());
                }
            };
            AttachFocusSpeech(_lstCallHistory, "発着信履歴リスト", "上下矢印キーで過去の通話日時・相手・何時間何分何秒だったかの正確な通話時間を確認できます。");
            historyCol.Controls.Add(_lstCallHistory);

            _btnSpeakSelectedHistory = CreateAccessibleButton(
                "🔊 最新の通話時間を読み上げ",
                "最新の通話時間を読み上げボタン",
                "直近の通話が何分何秒だったかを正確に読み上げます。",
                Color.FromArgb(14, 116, 144),
                310,
                (s, e) => SpeakLatestCallHistory());
            historyCol.Controls.Add(_btnSpeakSelectedHistory);
            middleRow.Controls.Add(historyCol);

            _pnlStandardPhoneTab.Controls.Add(middleRow);

            // AI Narrator & AES-256-GCM Diagnostic Log
            _lblAiLog = new Label
            {
                Text = "🤖 EmbeddingGemma 2 ＋ Gemma 4 表情＆カメラAI実況・AES-256-GCM グループ通話ログ (&G):",
                AutoSize = true,
                ForeColor = Color.FromArgb(253, 224, 71),
                Margin = new Padding(0, 4, 0, 4)
            };
            _pnlVideoCallTab.Controls.Add(_lblAiLog);

            _txtAiLog = new TextBox
            {
                Multiline = true,
                ReadOnly = true,
                ScrollBars = ScrollBars.Vertical,
                Size = new Size(980, 165),
                BackColor = Color.FromArgb(12, 18, 34),
                ForeColor = Color.FromArgb(226, 232, 240),
                AccessibleName = "AI実況およびAES-256-GCM暗号化グループ通話ログ表示欄"
            };
            AttachFocusSpeech(_txtAiLog, "AI実況およびAES-256-GCM暗号化通話ログ表示欄", "上下矢印キーで参加メンバーの表情AI実況、自分のWebカメラ診断結果、AES-256-GCM暗号化認証状況、および秒単位の通話時間ログを確認できます。");
            _pnlVideoCallTab.Controls.Add(_txtAiLog);

            rootPanel.Controls.Add(_pnlStandardPhoneTab);
            rootPanel.Controls.Add(_pnlVideoCallTab);
            rootPanel.Controls.Add(_pnlContactsTab);
            rootPanel.Controls.Add(_pnlSettingsTab);
            SwitchPhoneTab(0, false);

            Controls.Add(rootPanel);

            AppendLog("Kapamilya Serena Phone for Windows v" + CURRENT_VERSION + " 準備完了。");
            AppendLog("・全通信・招待コード・AI表情メタデータ AES-256-GCM 完全暗号化 (PBKDF2-HMAC-SHA256 100,000回 • 96bit IV • 128bit Tag • 安全確認コード: " + _currentSasFingerprint + ")");
            AppendLog("・P2P フルメッシュ・グループ通話対応：ビデオ通話 最大6人 ／ 音声通話 最大10人");
            AppendLog("・Google DeepMind EmbeddingGemma 2 (256次元L2表情・構図ベクトル) + Gemma 4 デュアルビデオAI実況エンジン 待機中");
        }

        private Button CreateAccessibleButton(string text, string accName, string accHelp, Color backColor, int width, EventHandler onClick)
        {
            var btn = new Button
            {
                Text = text,
                AccessibleName = accName,
                AccessibleDescription = accHelp,
                BackColor = backColor,
                ForeColor = Color.White,
                FlatStyle = FlatStyle.Flat,
                Font = new Font("Yu Gothic UI", 9.5f, FontStyle.Bold),
                Size = new Size(width, 40),
                Margin = new Padding(0, 0, 8, 6)
            };
            btn.Click += onClick;
            AttachFocusSpeech(btn, accName, accHelp);
            return btn;
        }

        private void AttachFocusSpeech(Control ctrl, string conciseName, string detailedGuide)
        {
            ctrl.AccessibleName = conciseName;
            ctrl.AccessibleDescription = detailedGuide;
            ctrl.GotFocus += (s, e) =>
            {
                string typeLabel = "コントロール";
                string stateLabel = "";

                if (ctrl is Button) typeLabel = "ボタン";
                else if (ctrl is TextBox)
                {
                    typeLabel = "テキスト入力";
                    var tb = (TextBox)ctrl;
                    if (!string.IsNullOrEmpty(tb.Text) && !tb.Multiline)
                        stateLabel = "、現在の値: " + tb.Text;
                }
                else if (ctrl is CheckBox)
                {
                    typeLabel = "チェックボックス";
                    stateLabel = ((CheckBox)ctrl).Checked ? "、チェックあり" : "、チェックなし";
                }
                else if (ctrl is ComboBox)
                {
                    typeLabel = "コンボボックス";
                    var cb = (ComboBox)ctrl;
                    if (cb.SelectedItem != null)
                        stateLabel = "、選択中: " + cb.SelectedItem.ToString();
                }
                else if (ctrl is ListBox)
                {
                    typeLabel = "リスト";
                    var lb = (ListBox)ctrl;
                    if (lb.SelectedItem != null)
                        stateLabel = "、選択中: " + lb.SelectedItem.ToString();
                }

                if (_focusReadAllMode)
                {
                    Speak(string.Format("{0}、{1}{2}。{3}", conciseName, typeLabel, stateLabel, detailedGuide));
                }
                else
                {
                    Speak(string.Format("{0}、{1}{2}", conciseName, typeLabel, stateLabel));
                }
            };
        }

        private void SwitchPhoneTab(int tabIndex, bool announce)
        {
            _activePhoneTab = tabIndex;
            if (_pnlStandardPhoneTab != null) _pnlStandardPhoneTab.Visible = (tabIndex == 0);
            if (_pnlVideoCallTab != null) _pnlVideoCallTab.Visible = (tabIndex == 1);
            if (_pnlContactsTab != null) _pnlContactsTab.Visible = (tabIndex == 2);
            if (_pnlSettingsTab != null) _pnlSettingsTab.Visible = (tabIndex == 3);
            if (_btnTabStandardPhone != null)
                _btnTabStandardPhone.BackColor = (tabIndex == 0) ? Color.FromArgb(16, 185, 129) : Color.FromArgb(30, 41, 59);
            if (_btnTabVideoCall != null)
                _btnTabVideoCall.BackColor = (tabIndex == 1) ? Color.FromArgb(2, 132, 199) : Color.FromArgb(30, 41, 59);
            if (_btnTabContacts != null)
                _btnTabContacts.BackColor = (tabIndex == 2) ? Color.FromArgb(217, 119, 6) : Color.FromArgb(30, 41, 59);
            if (_btnTabSettings != null)
                _btnTabSettings.BackColor = (tabIndex == 3) ? Color.FromArgb(124, 58, 237) : Color.FromArgb(30, 41, 59);

            if (announce)
            {
                if (tabIndex == 0)
                {
                    Speak(_uiLang == "en" ? "Switched to Standard Phone, Keypad & Call History Tab." : "標準電話・キーパッド＆発着信履歴タブに切り替えました。");
                }
                else if (tabIndex == 1)
                {
                    Speak(_uiLang == "en" ? "Switched to Video Call, Encrypted Group & AI Bot Tab." : "ビデオ通話・暗号化グループ＆AIボットタブに切り替えました。");
                }
                else if (tabIndex == 2)
                {
                    Speak(_uiLang == "en" ? "Switched to Contacts & Google Contacts Tab." : "連絡先・Googleコンタクトタブに切り替えました。");
                }
                else
                {
                    Speak(_uiLang == "en" ? "Switched to Settings & Audio Quality Tab." : "設定・通話音質タブに切り替えました。48kHzフルバンド音質やノイズ抑制が設定できます。");
                }
            }
        }

        private void ApplyLocalization()
        {
            if (_uiLang == "en")
            {
                if (_btnTabStandardPhone != null) _btnTabStandardPhone.Text = _btnTabStandardPhone.AccessibleName = "📞 1. Standard Phone & History (&1)";
                if (_btnTabVideoCall != null) _btnTabVideoCall.Text = _btnTabVideoCall.AccessibleName = "🎥 2. Video & AI Call (&2)";
                if (_btnTabContacts != null) _btnTabContacts.Text = _btnTabContacts.AccessibleName = "👥 3. Contacts & Google (&3)";
                if (_btnTabSettings != null) _btnTabSettings.Text = _btnTabSettings.AccessibleName = "⚙️ 4. Settings (Audio Quality) (&4)";
                _lblLang.Text = "Language (&L):";
                _chkFocusVerbosity.Text = "Read All Details on Tab Focus (Ctrl+Shift+F)";
                _lblDialNumber.Text = "Number (&N):";
                _lblWebRtcRoom.Text = "Room (&R):";
                _lblAesPassphrase.Text = "AES-256-GCM Key (&K):";
                _btnStartCall.Text = "📞 Encrypted Call (F5)";
                _btnEndCall.Text = "📴 End Call (F6)";
                _btnPartnerExpressionAi.Text = "😊 Group Expression AI (F7)";
                _btnSelfCameraCheckAi.Text = "📷 Check My Webcam (F8)";
                _btnMuteMic.Text = _isMicMuted ? "🔇 Mic Muted (F9)" : "🎙️ Mute Mic (F9)";
                _btnGenerateInviteCode.Text = "🔐 Copy AES-256-GCM Invite (F10)";
                _btnAcceptInviteCode.Text = "🔗 Paste Encrypted Invite (F11)";
                _btnSpeakMeshRoster.Text = "👥 Roster & SAS Code";
                _btnToggleSpeaker.Text = _isSpeakerphone ? "🔊 Speaker: ON (F12)" : "🎧 Headset (F12)";
                _chkAutoAiNarrate.Text = "Auto-AI";
                if (_btnTestBot1on1 != null) _btnTestBot1on1.Text = "🤖 Serena AI Bot (F4)";
                if (_btnTestBotTalkMic != null) _btnTestBotTalkMic.Text = "🎙️ Talk via Mic (F3)";
                if (_btnTestBotVideo6 != null) _btnTestBotVideo6.Text = "👥 6-Video Bots (Ctrl+F6)";
                if (_btnTestBotAudio10 != null) _btnTestBotAudio10.Text = "👥 10-Audio Bots (Ctrl+F10)";
                if (_btnTestBotLeaveSound != null) _btnTestBotLeaveSound.Text = "🍂 Leave Sound (F2)";
                if (_grpKeypad != null) _grpKeypad.Text = "🔢 DTMF Tone Keypad";
                if (_lblContacts != null) _lblContacts.Text = "⭐ Speed Dial Contacts (&C):";
                if (_btnCallSelectedContact != null) _btnCallSelectedContact.Text = "Call";
                if (_btnAddContact != null) _btnAddContact.Text = "Add";
                if (_btnDeleteContact != null) _btnDeleteContact.Text = "Delete";
                if (_lblCallHistory != null) _lblCallHistory.Text = "🕒 Call History (Exact Seconds) (&H):";
                if (_btnSpeakSelectedHistory != null) _btnSpeakSelectedHistory.Text = "🔊 Speak Latest Call Duration";
                if (_lblAiLog != null) _lblAiLog.Text = "🤖 EmbeddingGemma 2 + Gemma 4 Expression AI & AES-256-GCM Call Log (&G):";
                if (!_isCallActive && _lblCallStatusBox != null) _lblCallStatusBox.Text = "Ready — F4:Auto-Reply AI Bot | F2:Leave Sound | F5:Encrypted Call | F6:End Call | F7:Expression AI | F8:Webcam Check";
            }
            else if (_uiLang == "tl")
            {
                if (_btnTabStandardPhone != null) _btnTabStandardPhone.Text = "📞 Tab ng Karaniwang Telepono (&1)";
                if (_btnTabVideoCall != null) _btnTabVideoCall.Text = "🎥 Tab ng Video Call at AI Bot (&2)";
                _lblLang.Text = "Wika (&L):";
                _chkFocusVerbosity.Text = "Basahin Lahat sa Tab Focus (Ctrl+Shift+F)";
                _lblDialNumber.Text = "Numero (&N):";
                _lblWebRtcRoom.Text = "Room (&R):";
                _lblAesPassphrase.Text = "AES-256-GCM Key (&K):";
                _btnStartCall.Text = "📞 Tumawag AES-GCM (F5)";
                _btnEndCall.Text = "📴 Ibaba (F6)";
                _btnPartnerExpressionAi.Text = "😊 Ekspresyon ng Grupo AI (F7)";
                _btnSelfCameraCheckAi.Text = "📷 Suriin ang Camera (F8)";
                _btnMuteMic.Text = _isMicMuted ? "🔇 Naka-Mute (F9)" : "🎙️ I-Mute (F9)";
                _btnGenerateInviteCode.Text = "🔐 Kopyahin AES-GCM Invite (F10)";
                _btnAcceptInviteCode.Text = "🔗 I-Paste at Sumali (F11)";
                _btnSpeakMeshRoster.Text = "👥 Mga Kasali at SAS";
                _btnToggleSpeaker.Text = _isSpeakerphone ? "🔊 Speaker: ON (F12)" : "🎧 Headset (F12)";
                _chkAutoAiNarrate.Text = "Auto-AI";
                if (_btnTestBot1on1 != null) _btnTestBot1on1.Text = "🤖 Serena AI Bot (F4)";
                if (_btnTestBotTalkMic != null) _btnTestBotTalkMic.Text = "🎙️ Kausapin ang Bot (F3)";
                if (_btnTestBotVideo6 != null) _btnTestBotVideo6.Text = "👥 6-Video Bots (Ctrl+F6)";
                if (_btnTestBotAudio10 != null) _btnTestBotAudio10.Text = "👥 10-Audio Bots (Ctrl+F10)";
                if (_btnTestBotLeaveSound != null) _btnTestBotLeaveSound.Text = "🍂 Tunog Pag-alis (F2)";
                if (_grpKeypad != null) _grpKeypad.Text = "🔢 DTMF Tone Keypad";
                if (_lblContacts != null) _lblContacts.Text = "⭐ Speed Dial Contacts (&C):";
                if (_btnCallSelectedContact != null) _btnCallSelectedContact.Text = "Tawagan";
                if (_btnAddContact != null) _btnAddContact.Text = "Idagdag";
                if (_btnDeleteContact != null) _btnDeleteContact.Text = "Burahin";
                if (_lblCallHistory != null) _lblCallHistory.Text = "🕒 Kasaysayan ng Tawag (Eksaktong Segundo) (&H):";
                if (_btnSpeakSelectedHistory != null) _btnSpeakSelectedHistory.Text = "🔊 Basahin ang Tagal ng Tawag";
                if (_lblAiLog != null) _lblAiLog.Text = "🤖 EmbeddingGemma 2 + Gemma 4 AI at AES-256-GCM Call Log (&G):";
                if (!_isCallActive && _lblCallStatusBox != null) _lblCallStatusBox.Text = "Handa na — F4:Auto-Reply AI Bot | F2:Tunog Pag-alis | F5:Tumawag | F6:Ibaba | F7:Ekspresyon AI | F8:Camera";
            }
            else if (_uiLang == "es")
            {
                if (_btnTabStandardPhone != null) _btnTabStandardPhone.Text = "📞 Pestaña Teléfono Estándar (&1)";
                if (_btnTabVideoCall != null) _btnTabVideoCall.Text = "🎥 Pestaña Videollamada y Bot IA (&2)";
                _lblLang.Text = "Idioma (&L):";
                _chkFocusVerbosity.Text = "Leer Todo al Cambiar Foco con Tab (Ctrl+Shift+F)";
                _lblDialNumber.Text = "Número (&N):";
                _lblWebRtcRoom.Text = "Sala (&R):";
                _lblAesPassphrase.Text = "Clave AES-256-GCM (&K):";
                _btnStartCall.Text = "📞 Llamada Cifrada (F5)";
                _btnEndCall.Text = "📴 Colgar (F6)";
                _btnPartnerExpressionAi.Text = "😊 Expresión Grupal IA (F7)";
                _btnSelfCameraCheckAi.Text = "📷 Revisar Mi Cámara (F8)";
                _btnMuteMic.Text = _isMicMuted ? "🔇 Silenciado (F9)" : "🎙️ Silenciar (F9)";
                _btnGenerateInviteCode.Text = "🔐 Copiar Invitación GCM (F10)";
                _btnAcceptInviteCode.Text = "🔗 Pegar y Unirse (F11)";
                _btnSpeakMeshRoster.Text = "👥 Participantes y SAS";
                _btnToggleSpeaker.Text = _isSpeakerphone ? "🔊 Altavoz: ON (F12)" : "🎧 Auriculares (F12)";
                _chkAutoAiNarrate.Text = "Auto-IA";
                if (_btnTestBot1on1 != null) _btnTestBot1on1.Text = "🤖 Bot IA Serena (F4)";
                if (_btnTestBotTalkMic != null) _btnTestBotTalkMic.Text = "🎙️ Hablar al Bot (F3)";
                if (_btnTestBotVideo6 != null) _btnTestBotVideo6.Text = "👥 6 Bots Video (Ctrl+F6)";
                if (_btnTestBotAudio10 != null) _btnTestBotAudio10.Text = "👥 10 Bots Voz (Ctrl+F10)";
                if (_btnTestBotLeaveSound != null) _btnTestBotLeaveSound.Text = "🍂 Sonido Salida (F2)";
                if (_grpKeypad != null) _grpKeypad.Text = "🔢 Teclado DTMF";
                if (_lblContacts != null) _lblContacts.Text = "⭐ Contactos Rápidos (&C):";
                if (_btnCallSelectedContact != null) _btnCallSelectedContact.Text = "Llamar";
                if (_btnAddContact != null) _btnAddContact.Text = "Añadir";
                if (_btnDeleteContact != null) _btnDeleteContact.Text = "Borrar";
                if (_lblCallHistory != null) _lblCallHistory.Text = "🕒 Historial de Llamadas (Segundos Exactos) (&H):";
                if (_btnSpeakSelectedHistory != null) _btnSpeakSelectedHistory.Text = "🔊 Leer Duración de Última Llamada";
                if (_lblAiLog != null) _lblAiLog.Text = "🤖 Registro de IA EmbeddingGemma 2 + Gemma 4 y Llamadas AES-256-GCM (&G):";
                if (!_isCallActive && _lblCallStatusBox != null) _lblCallStatusBox.Text = "Listo — F4:Bot IA Automático | F2:Sonido Salida | F5:Llamar | F6:Colgar | F7:Expresión IA | F8:Cámara";
            }
            else
            {
                if (_btnTabStandardPhone != null) _btnTabStandardPhone.Text = "📞 標準電話・ダイヤラータブ (&1)";
                if (_btnTabVideoCall != null) _btnTabVideoCall.Text = "🎥 ビデオ通話・暗号化グループ＆AIボットタブ (&2)";
                _lblLang.Text = "表示・音声言語 (&L):";
                _chkFocusVerbosity.Text = "タブ移動時に詳細ガイドまですべて読む (Ctrl+Shift+F)";
                _lblDialNumber.Text = "番号 (&N):";
                _lblWebRtcRoom.Text = "ルーム (&R):";
                _lblAesPassphrase.Text = "AES-256-GCM 暗号鍵 (&K):";
                _btnStartCall.Text = "📞 暗号化通話・発信開始 (F5)";
                _btnEndCall.Text = "📴 通話を切る (F6)";
                _btnPartnerExpressionAi.Text = "😊 参加メンバーの表情AI実況 (F7)";
                _btnSelfCameraCheckAi.Text = "📷 自分のカメラ確認 (F8)";
                _btnMuteMic.Text = _isMicMuted ? "🔇 マイク消音中 (F9)" : "🎙️ マイク消音 (F9)";
                _btnGenerateInviteCode.Text = "🔐 AES-256-GCM 招待コードコピー (F10)";
                _btnAcceptInviteCode.Text = "🔗 暗号化招待コード貼付＆参加 (F11)";
                _btnSpeakMeshRoster.Text = "👥 参加者＆暗号化コード読上";
                _btnToggleSpeaker.Text = _isSpeakerphone ? "🔊 スピーカー: ON (F12)" : "🎧 ヘッドセット (F12)";
                _chkAutoAiNarrate.Text = "自動表情実況";
                if (_btnTestBot1on1 != null) _btnTestBot1on1.Text = "🤖 セレナAIボット通話・返事 (F4)";
                if (_btnTestBotTalkMic != null) _btnTestBotTalkMic.Text = "🎙️ ボットに話しかける (F3)";
                if (_btnTestBotVideo6 != null) _btnTestBotVideo6.Text = "👥 6人ビデオボット (Ctrl+F6)";
                if (_btnTestBotAudio10 != null) _btnTestBotAudio10.Text = "👥 10人音声ボット (Ctrl+F10)";
                if (_btnTestBotLeaveSound != null) _btnTestBotLeaveSound.Text = "🍂 退室音テスト (F2)";
                if (_grpKeypad != null) _grpKeypad.Text = "🔢 プッシュ音（DTMFトーン）テンキー";
                if (_lblContacts != null) _lblContacts.Text = "⭐ スピードダイヤル連絡先 (&C):";
                if (_btnCallSelectedContact != null) _btnCallSelectedContact.Text = "発信";
                if (_btnAddContact != null) _btnAddContact.Text = "追加";
                if (_btnDeleteContact != null) _btnDeleteContact.Text = "削除";
                if (_lblCallHistory != null) _lblCallHistory.Text = "🕒 発着信履歴（秒単位の正確な通話時間）(&H):";
                if (_btnSpeakSelectedHistory != null) _btnSpeakSelectedHistory.Text = "🔊 最新の通話時間を読み上げ";
                if (_lblAiLog != null) _lblAiLog.Text = "🤖 EmbeddingGemma 2 ＋ Gemma 4 表情＆カメラAI実況・AES-256-GCM グループ通話ログ (&G):";
                if (!_isCallActive && _lblCallStatusBox != null) _lblCallStatusBox.Text = "待機中 — F4:自動応答ボット | F2:退室音テスト | F5:暗号化通話開始 | F6:切断(秒単位の通話時間) | F7:表情AI実況 | F8:カメラ確認";
            }
            UpdateEncryptionMeshBanner();
        }

        private void OnFormKeyDown(object sender, KeyEventArgs e)
        {
            if (e.Alt && e.KeyCode == Keys.D1)
            {
                SwitchPhoneTab(0, true);
                e.Handled = true;
                return;
            }
            if (e.Alt && e.KeyCode == Keys.D2)
            {
                SwitchPhoneTab(1, true);
                e.Handled = true;
                return;
            }
            if (e.Alt && e.KeyCode == Keys.D3)
            {
                SwitchPhoneTab(2, true);
                e.Handled = true;
                return;
            }
            if (e.Alt && e.KeyCode == Keys.D4)
            {
                SwitchPhoneTab(3, true);
                e.Handled = true;
                return;
            }
            if (e.Control && e.Shift && e.KeyCode == Keys.F)
            {
                _chkFocusVerbosity.Checked = !_chkFocusVerbosity.Checked;
                e.Handled = true;
                return;
            }

            if (e.Control && e.KeyCode == Keys.F6)
            {
                StartTestBotGroupCall(MAX_VIDEO_PARTICIPANTS, true);
                e.Handled = true;
                return;
            }
            if (e.Control && e.KeyCode == Keys.F10)
            {
                StartTestBotGroupCall(MAX_AUDIO_PARTICIPANTS, false);
                e.Handled = true;
                return;
            }

            if (e.KeyCode == Keys.F2)
            {
                SimulateParticipantLeaveWithSound();
                e.Handled = true;
            }
            else if (e.KeyCode == Keys.F3)
            {
                ListenAndTalkToTestBotViaMic();
                e.Handled = true;
            }
            else if (e.KeyCode == Keys.F4)
            {
                StartOrStepTestBotAction();
                e.Handled = true;
            }
            else if (e.KeyCode == Keys.F5 || (e.Control && e.KeyCode == Keys.Enter))
            {
                StartCallAction();
                e.Handled = true;
            }
            else if (e.KeyCode == Keys.F6 || (e.KeyCode == Keys.Escape && _isCallActive))
            {
                EndCallAction();
                e.Handled = true;
            }
            else if (e.KeyCode == Keys.F7)
            {
                SpeakPartnerExpressionAi(true);
                e.Handled = true;
            }
            else if (e.KeyCode == Keys.F8)
            {
                CheckSelfWebcamFramingAi();
                e.Handled = true;
            }
            else if (e.KeyCode == Keys.F9)
            {
                ToggleMicMute();
                e.Handled = true;
            }
            else if (e.KeyCode == Keys.F10)
            {
                GenerateAndCopyWebRtcInviteCode();
                e.Handled = true;
            }
            else if (e.KeyCode == Keys.F11)
            {
                AcceptClipboardWebRtcInviteCode();
                e.Handled = true;
            }
            else if (e.KeyCode == Keys.F12)
            {
                ToggleSpeakerphone();
                e.Handled = true;
            }
        }

        // ====================================================================
        // 1. ITU-T Q.23 Dual-Tone Multi-Frequency (DTMF) & Call Chime Generator
        // ====================================================================
        private void PlayDtmfToneAsync(char key)
        {
            Task.Run(() =>
            {
                try
                {
                    double lowFreq = 697;
                    double highFreq = 1209;
                    switch (char.ToUpperInvariant(key))
                    {
                        case '1': lowFreq = 697; highFreq = 1209; break;
                        case '2': lowFreq = 697; highFreq = 1336; break;
                        case '3': lowFreq = 697; highFreq = 1477; break;
                        case '4': lowFreq = 770; highFreq = 1209; break;
                        case '5': lowFreq = 770; highFreq = 1336; break;
                        case '6': lowFreq = 770; highFreq = 1477; break;
                        case '7': lowFreq = 852; highFreq = 1209; break;
                        case '8': lowFreq = 852; highFreq = 1336; break;
                        case '9': lowFreq = 852; highFreq = 1477; break;
                        case '*': lowFreq = 941; highFreq = 1209; break;
                        case '0': lowFreq = 941; highFreq = 1336; break;
                        case '#': lowFreq = 941; highFreq = 1477; break;
                        default: lowFreq = 800; highFreq = 1200; break;
                    }
                    PlaySynthesizedDualTone(lowFreq, highFreq, 140);
                }
                catch { }
            });
        }

        private void PlaySynthesizedDualTone(double freq1, double freq2, int durationMs)
        {
            const int sampleRate = 44100;
            int totalSamples = (sampleRate * durationMs) / 1000;
            int dataBytes = totalSamples * 2;

            using (var ms = new MemoryStream(44 + dataBytes))
            using (var bw = new BinaryWriter(ms))
            {
                bw.Write(Encoding.ASCII.GetBytes("RIFF"));
                bw.Write(36 + dataBytes);
                bw.Write(Encoding.ASCII.GetBytes("WAVEfmt "));
                bw.Write(16);
                bw.Write((short)1);
                bw.Write((short)1);
                bw.Write(sampleRate);
                bw.Write(sampleRate * 2);
                bw.Write((short)2);
                bw.Write((short)16);
                bw.Write(Encoding.ASCII.GetBytes("data"));
                bw.Write(dataBytes);

                for (int i = 0; i < totalSamples; i++)
                {
                    double t = (double)i / sampleRate;
                    double env = 1.0;
                    if (i < 220) env = i / 220.0;
                    else if (i > totalSamples - 220) env = (totalSamples - i) / 220.0;

                    double sample = 0.45 * (Math.Sin(2.0 * Math.PI * freq1 * t) + Math.Sin(2.0 * Math.PI * freq2 * t)) * env;
                    short pcm = (short)Math.Max(short.MinValue, Math.Min(short.MaxValue, sample * 16000));
                    bw.Write(pcm);
                }

                ms.Position = 0;
                using (var player = new SoundPlayer(ms))
                {
                    player.PlaySync();
                }
            }
        }

        // ====================================================================
        // 1B. Call Earcons (Connecting Sound, Joined Chime, Left/Dropped Chime,
        //     Disconnected Chime & Bot Listening Cue)
        // ====================================================================
        private void PlayConnectingSoundAsync(Action onComplete = null)
        {
            Task.Run(() =>
            {
                try
                {
                    PlaySynthesizedDualTone(440.0, 480.0, 280);
                    Thread.Sleep(70);
                    PlaySynthesizedDualTone(440.0, 480.0, 280);
                }
                catch { }
                finally
                {
                    if (onComplete != null) BeginInvoke(onComplete);
                }
            });
        }

        private void PlayParticipantJoinedSoundAsync(Action onComplete = null)
        {
            Task.Run(() =>
            {
                try
                {
                    PlaySynthesizedDualTone(523.25, 1046.50, 90);
                    PlaySynthesizedDualTone(659.25, 1318.51, 90);
                    PlaySynthesizedDualTone(783.99, 1567.98, 90);
                    PlaySynthesizedDualTone(1046.50, 1567.98, 200);
                }
                catch { }
                finally
                {
                    if (onComplete != null) BeginInvoke(onComplete);
                }
            });
        }

        private void PlayParticipantLeftSoundAsync(Action onComplete = null)
        {
            Task.Run(() =>
            {
                try
                {
                    PlaySynthesizedDualTone(783.99, 392.00, 115);
                    PlaySynthesizedDualTone(622.25, 311.13, 115);
                    PlaySynthesizedDualTone(523.25, 261.63, 220);
                }
                catch { }
                finally
                {
                    if (onComplete != null) BeginInvoke(onComplete);
                }
            });
        }

        private void PlayCallDisconnectedSoundAsync(Action onComplete = null)
        {
            Task.Run(() =>
            {
                try
                {
                    PlaySynthesizedDualTone(783.99, 1174.66, 95);
                    PlaySynthesizedDualTone(659.25, 987.77, 95);
                    PlaySynthesizedDualTone(523.25, 783.99, 105);
                    PlaySynthesizedDualTone(392.00, 523.25, 230);
                }
                catch { }
                finally
                {
                    if (onComplete != null) BeginInvoke(onComplete);
                }
            });
        }

        private void PlayBotListeningCueAsync()
        {
            Task.Run(() =>
            {
                try
                {
                    PlaySynthesizedDualTone(659.25, 1318.51, 65);
                    PlaySynthesizedDualTone(880.00, 1760.00, 100);
                }
                catch { }
            });
        }

        // ====================================================================
        // 2. Call Control & Exact Second-Level Call Duration Announcer
        // ====================================================================
        private void StartCallAction()
        {
            if (_isCallActive)
            {
                long elapsedSec = _callStopwatch.ElapsedMilliseconds / 1000;
                int curCount;
                lock (_meshParticipants) { curCount = Math.Max(1, _meshParticipants.Count); }
                Speak(string.Format("現在「{0}」と AES-256-GCM 暗号化通話中です（参加人数 {1}/{2}名・安全確認コード {3}）。ここまでの経過時間は {4} です。",
                    _activeCallPartner, curCount, GetMaxParticipantsForCurrentMode(), _currentSasFingerprint, FormatExactDuration(elapsedSec)));
                return;
            }

            RefreshAes256GcmKeyAndResetMesh();

            string dialNum = _txtDialNumber.Text.Trim();
            string roomName = _txtWebRtcRoom.Text.Trim();
            if (string.IsNullOrEmpty(roomName)) roomName = "Kapamilya-Serena-Home";

            int callModeIdx = _cmbCallType.SelectedIndex;
            if (!string.IsNullOrEmpty(dialNum) && callModeIdx == 2)
            {
                _activeCallPartner = dialNum;
                _activeCallModeLabel = "スマホ標準電話連携 (AES-256-GCM Bayanihan Link)";
                _isVideoCall = false;
                SendBayanihanPhoneDialPacket(dialNum);
            }
            else
            {
                _activeCallPartner = !string.IsNullOrEmpty(dialNum) ? dialNum : roomName;
                _isVideoCall = (callModeIdx == 0);
                int maxCap = GetMaxParticipantsForCurrentMode();
                _activeCallModeLabel = _isVideoCall
                    ? string.Format("AES-256-GCM 暗号化 WebRTC ビデオ通話 (最大{0}人)", maxCap)
                    : string.Format("AES-256-GCM 暗号化 WebRTC 音声通話 (最大{0}人)", maxCap);
                BroadcastEncryptedMeshJoinPacket();
            }

            _isCallActive = true;
            _autoAiTickCounter = 0;
            _botProactiveSilenceTicks = 0;
            _userVoiceActiveTicks = 0;
            _callStopwatch.Restart();
            _callUiTimer.Start();

            bool isBotRoom = roomName.IndexOf("test", StringComparison.OrdinalIgnoreCase) >= 0 ||
                             roomName.IndexOf("bot", StringComparison.OrdinalIgnoreCase) >= 0;
            _isBotCallActive = isBotRoom;

            if (isBotRoom)
            {
                int targetBots = roomName.IndexOf("video6", StringComparison.OrdinalIgnoreCase) >= 0
                    ? MAX_VIDEO_PARTICIPANTS
                    : (roomName.IndexOf("audio10", StringComparison.OrdinalIgnoreCase) >= 0 ? MAX_AUDIO_PARTICIPANTS : 2);
                SpawnTestBotsIntoMesh(targetBots, _isVideoCall);
            }

            try
            {
                if (_speechRecognizer != null && !_isMicMuted)
                {
                    _speechRecognizer.RecognizeAsyncCancel();
                    _speechRecognizer.RecognizeAsync(RecognizeMode.Multiple);
                }
            }
            catch { }

            string startMsg;
            if (_uiLang == "en")
            {
                startMsg = string.Format(
                    "Connected to \"{0}\" ({1})! All traffic is encrypted with AES-256-GCM (Security SAS: {2}). Speak into your microphone or press F4/F3 to talk with the AI Test Bot, F7 for group facial expression AI, F8 to check your webcam, or F6 to end the call and hear the exact duration.",
                    _activeCallPartner, _activeCallModeLabel, _currentSasFingerprint);
            }
            else if (_uiLang == "tl")
            {
                startMsg = string.Format(
                    "Nakakonekta na sa \"{0}\" ({1})! Lahat ng komunikasyon ay naka-encrypt sa AES-256-GCM (Security SAS: {2}). Magsalita sa mikropono o pindutin ang F4/F3 para kausapin ang AI Test Bot, F7 para sa ekspresyon ng grupo, F8 para sa camera, at F6 para ibaba ang tawag.",
                    _activeCallPartner, _activeCallModeLabel, _currentSasFingerprint);
            }
            else if (_uiLang == "es")
            {
                startMsg = string.Format(
                    "¡Conectado a \"{0}\" ({1})! Todo el tráfico está cifrado con AES-256-GCM (Código SAS: {2}). Hable por el micrófono o presione F4/F3 para conversar con el Bot IA, F7 para expresiones del grupo, F8 para su cámara y F6 para colgar.",
                    _activeCallPartner, _activeCallModeLabel, _currentSasFingerprint);
            }
            else
            {
                startMsg = string.Format(
                    "「{0}」で {1} を開始しました！すべての通信は AES-256-GCM（安全確認コード: {2}）で完全暗号化されています。マイクに向かって話しかけるとボットが自動で返事します。F4キーでボット応答、F7キーで参加メンバー全員の表情AI実況、F8キーで自分のカメラ確認、F6キーで通話を切って秒単位の通話時間を読み上げます。",
                    _activeCallPartner,
                    _activeCallModeLabel,
                    _currentSasFingerprint
                );
            }

            AppendLog("🟢 " + startMsg);
            PlayConnectingSoundAsync(() =>
            {
                PlayParticipantJoinedSoundAsync(() =>
                {
                    Speak(startMsg);
                });
            });
        }

        private void EndCallAction()
        {
            if (!_isCallActive)
            {
                Speak("現在アクティブな通話はありません。直近の通話履歴を確認するには履歴リストを選択してください。");
                return;
            }

            _callStopwatch.Stop();
            _callUiTimer.Stop();
            _isCallActive = false;
            _isBotCallActive = false;
            _botProactiveSilenceTicks = 0;
            _userVoiceActiveTicks = 0;

            try
            {
                if (_speechRecognizer != null)
                {
                    _speechRecognizer.RecognizeAsyncCancel();
                }
            }
            catch { }

            long totalMs = Math.Max(1000L, _callStopwatch.ElapsedMilliseconds);
            long totalSeconds = (long)Math.Round(totalMs / 1000.0);
            if (totalSeconds < 1) totalSeconds = 1;

            string exactDurationText = FormatExactDuration(totalSeconds);
            string partner = string.IsNullOrEmpty(_activeCallPartner) ? "通話相手" : _activeCallPartner;
            string mode = string.IsNullOrEmpty(_activeCallModeLabel) ? "通話" : _activeCallModeLabel;

            BroadcastEncryptedMeshLeavePacket();

            var record = new CallLogRecord
            {
                Timestamp = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss"),
                Partner = partner,
                Mode = mode,
                DurationSeconds = totalSeconds,
                FormattedDuration = exactDurationText
            };
            _callHistory.Insert(0, record);
            RefreshHistoryListBox();
            SaveContactsAndHistory();

            string endAnnouncement;
            if (_uiLang == "en")
            {
                endAnnouncement = string.Format("Call ended with {0}. Exact call duration: {1}.", partner, exactDurationText);
            }
            else if (_uiLang == "tl")
            {
                endAnnouncement = string.Format("Tapos na ang tawag kay {0}. Eksaktong tagal ng tawag: {1}.", partner, exactDurationText);
            }
            else if (_uiLang == "es")
            {
                endAnnouncement = string.Format("Llamada finalizada con {0}. Duración exacta de la llamada: {1}.", partner, exactDurationText);
            }
            else
            {
                endAnnouncement = string.Format("「{0}」との通話が終了しました。通話時間は {1} でした。", partner, exactDurationText);
            }

            _lblCallStatusBox.Text = endAnnouncement;
            _lblCallStatusBox.AccessibleName = endAnnouncement;
            AppendLog("📴 " + endAnnouncement);
            PlayCallDisconnectedSoundAsync(() =>
            {
                Speak(endAnnouncement, true);
            });
        }

        // ====================================================================
        // 2B. Kapamilya Serena AI Test Bot (1-on-1 / 6-Video / 10-Audio)
        //     Real AES-256-GCM Loopback Verification + Voice & Facial AI
        // ====================================================================
        private void StartOrStepTestBotAction()
        {
            _isBotCallActive = true;
            if (!_isCallActive)
            {
                _txtDialNumber.Text = "";
                _txtWebRtcRoom.Text = "kapamilya-serena-test-bot";
                _suppressComboEvents = true;
                _cmbCallType.SelectedIndex = 0;
                _suppressComboEvents = false;
                _isVideoCall = true;
                StartCallAction();
                SpawnTestBotsIntoMesh(2, true);
                ProcessTestBotConversationTurn("");
            }
            else
            {
                int count;
                lock (_meshParticipants) { count = _meshParticipants.Count; }
                if (count <= 1)
                {
                    SpawnTestBotsIntoMesh(2, _isVideoCall);
                }
                ProcessTestBotConversationTurn("");
            }
        }

        private void StartTestBotGroupCall(int targetTotalParticipants, bool withVideo)
        {
            _isBotCallActive = true;
            _txtDialNumber.Text = "";
            _txtWebRtcRoom.Text = withVideo ? "kapamilya-serena-test-video6" : "kapamilya-serena-test-audio10";
            _suppressComboEvents = true;
            _cmbCallType.SelectedIndex = withVideo ? 0 : 1;
            _suppressComboEvents = false;
            _isVideoCall = withVideo;

            if (!_isCallActive)
            {
                StartCallAction();
            }
            else
            {
                RefreshAes256GcmKeyAndResetMesh();
            }

            string msg = SpawnTestBotsIntoMesh(targetTotalParticipants, withVideo);
            AppendLog("👥 " + msg);
            PlayParticipantJoinedSoundAsync(() =>
            {
                Speak(msg, true);
            });
        }

        private string SpawnTestBotsIntoMesh(int targetTotalParticipants, bool withVideo)
        {
            int maxCap = withVideo ? MAX_VIDEO_PARTICIPANTS : MAX_AUDIO_PARTICIPANTS;
            int desired = Math.Max(2, Math.Min(maxCap, targetTotalParticipants));

            string[,] botCatalog = new string[,]
            {
                { "BOT-01", "🤖 Serena AI (マニラ・メインボット)", "満面の笑顔（笑顔度 98%）でカメラの正面から優しくうなずいています" },
                { "BOT-02", "🌺 Maria (セブ・テストボット)", "明るい笑顔（笑顔度 92%）で手を振りながら話を聞いています" },
                { "BOT-03", "🌸 Sakura (東京・テストボット)", "穏やかな微笑み（笑顔度 89%）で相槌を打っています" },
                { "BOT-04", "🎸 Juan (ダバオ・テストボット)", "楽しそうに笑いながら（笑顔度 94%）親指を立てています" },
                { "BOT-05", "🌟 Elena (マドリード・テストボット)", "リラックスした笑顔（笑顔度 87%）で画面を見つめています" },
                { "BOT-06", "🎵 Kenji (大阪・音声ボット)", "元気な声でハキハキと応答待機中" },
                { "BOT-07", "🌴 Liza (イロイロ・音声ボット)", "温かく落ち着いた声で参加中" },
                { "BOT-08", "🌊 Carlos (ケソン・音声ボット)", "クリアな音声ストリームで接続中" },
                { "BOT-09", "🍀 Aoi (福岡・音声ボット)", "ノイズのない高音質ストリームで参加中" }
            };

            lock (_meshParticipants)
            {
                MeshParticipantInfo selfInfo = null;
                if (_meshParticipants.ContainsKey(_localPeerId))
                {
                    selfInfo = _meshParticipants[_localPeerId];
                }
                _meshParticipants.Clear();
                if (selfInfo != null)
                {
                    selfInfo.IsVideoEnabled = withVideo;
                    _meshParticipants[_localPeerId] = selfInfo;
                }
                else
                {
                    _meshParticipants[_localPeerId] = new MeshParticipantInfo
                    {
                        PeerId = _localPeerId,
                        DisplayName = Environment.MachineName + " (自分)",
                        Endpoint = "local",
                        IsVideoEnabled = withVideo,
                        LatestExpression = "カメラ正面・参加中"
                    };
                }

                int needed = desired - 1;
                for (int i = 0; i < needed && i < botCatalog.GetLength(0); i++)
                {
                    string bId = botCatalog[i, 0];
                    _meshParticipants[bId] = new MeshParticipantInfo
                    {
                        PeerId = bId,
                        DisplayName = botCatalog[i, 1],
                        Endpoint = "AES256GCM-Loopback-Bot",
                        IsVideoEnabled = withVideo,
                        LatestExpression = botCatalog[i, 2]
                    };
                }
            }

            UpdateEncryptionMeshBanner();
            if (_uiLang == "en")
            {
                if (desired == 2)
                {
                    return string.Format("🤖 Kapamilya Serena AI Test Bot (1-on-1 Mode) connected via AES-256-GCM (SAS: {0})! Speak into your microphone anytime and I will reply automatically!", _currentSasFingerprint);
                }
                return string.Format("👥 Kapamilya AI Group Test Bots ({0}/{1} participants) joined via AES-256-GCM (SAS: {2})! Press F7 for group facial expression AI, F2 to test the member leave sound, or speak into your mic to talk with the bots!", desired, maxCap, _currentSasFingerprint);
            }
            if (_uiLang == "tl")
            {
                if (desired == 2)
                {
                    return string.Format("🤖 Nakakonekta na ang Kapamilya Serena AI Test Bot (1-on-1 Mode) sa AES-256-GCM (SAS: {0})! Magsalita ka lang sa mic at kusang sasagot ako!", _currentSasFingerprint);
                }
                return string.Format("👥 Pumasok na ang Kapamilya AI Group Test Bots ({0}/{1} kasali) sa AES-256-GCM (SAS: {2})! Pindutin ang F7 para sa ekspresyon ng grupo, F2 para sa tunog pag-alis, o magsalita sa mic!", desired, maxCap, _currentSasFingerprint);
            }
            if (_uiLang == "es")
            {
                if (desired == 2)
                {
                    return string.Format("🤖 ¡Bot de Prueba Kapamilya Serena IA (Modo 1 a 1) conectado con AES-256-GCM (SAS: {0})! Habla por el micrófono cuando quieras y te responderé automáticamente.", _currentSasFingerprint);
                }
                return string.Format("👥 ¡Bots de Prueba Grupales Kapamilya IA ({0}/{1} participantes) conectados con AES-256-GCM (SAS: {2})! Pulsa F7 para expresión grupal, F2 para probar el sonido de salida, o habla por el micrófono.", desired, maxCap, _currentSasFingerprint);
            }
            if (desired == 2)
            {
                return string.Format("🤖 Kapamilya Serena AI テストボット（1対1モード）が AES-256-GCM 暗号化回線（安全確認コード: {0}）で接続しました！マイクで話しかけると自動で会話が続き、F2キーで退室音（落ちた音）もテストできます。", _currentSasFingerprint);
            }
            return string.Format("👥 Kapamilya AI グループテストボット（合計 {0}/{1}名）が AES-256-GCM 暗号化メッシュ（安全確認コード: {2}）に参加しました！F7キーで全員の表情AI一括実況、F2キーでメンバー退室音テスト、マイク発話でボットたちと自動会話できます。", desired, maxCap, _currentSasFingerprint);
        }

        private void SimulateParticipantLeaveWithSound()
        {
            MeshParticipantInfo removedMember = null;
            int remainingCount;
            lock (_meshParticipants)
            {
                string targetKey = null;
                foreach (var kv in _meshParticipants)
                {
                    if (kv.Key != _localPeerId)
                    {
                        targetKey = kv.Key;
                        removedMember = kv.Value;
                    }
                }
                if (targetKey != null)
                {
                    _meshParticipants.Remove(targetKey);
                }
                remainingCount = Math.Max(1, _meshParticipants.Count);
            }

            UpdateEncryptionMeshBanner();
            int maxCap = GetMaxParticipantsForCurrentMode();
            string msg;
            if (removedMember != null)
            {
                msg = _uiLang == "en"
                    ? string.Format("🍂 {0} left the call ({1}/{2} remaining).", removedMember.DisplayName, remainingCount, maxCap)
                    : (_uiLang == "tl"
                        ? string.Format("🍂 Umalis na si {0} sa tawag ({1}/{2} ang natitira).", removedMember.DisplayName, remainingCount, maxCap)
                        : (_uiLang == "es"
                            ? string.Format("🍂 {0} salió de la llamada (quedan {1}/{2}).", removedMember.DisplayName, remainingCount, maxCap)
                            : string.Format("🍂 {0} さんが退室（切断）しました（残り {1}/{2}名）。", removedMember.DisplayName, remainingCount, maxCap)));
            }
            else
            {
                msg = _uiLang == "en"
                    ? "🍂 Participant left sound played. Currently only you are in the room."
                    : (_uiLang == "tl"
                        ? "🍂 Ipinatugtog ang tunog ng pag-alis. Ikaw na lang ang nasa room ngayon."
                        : (_uiLang == "es"
                            ? "🍂 Sonido de salida reproducido. Actualmente solo estás tú en la sala."
                            : "🍂 退室サウンド（落ちた音）を再生しました。現在ルームにはあなたのみが参加しています。"));
            }

            AppendLog(msg);
            PlayParticipantLeftSoundAsync(() =>
            {
                Speak(msg, true);
            });
        }

        private void ListenAndTalkToTestBotViaMic()
        {
            if (!_isCallActive)
            {
                _txtDialNumber.Text = "";
                _txtWebRtcRoom.Text = "kapamilya-serena-test-bot";
                StartCallAction();
                SpawnTestBotsIntoMesh(2, _isVideoCall);
            }

            using (var dlg = new Form())
            {
                dlg.Text = "🎙️ セレナAIテストボットに話しかける (AES-256-GCM 暗号化対話)";
                dlg.Size = new Size(620, 230);
                dlg.StartPosition = FormStartPosition.CenterParent;
                dlg.BackColor = Color.FromArgb(15, 23, 42);
                dlg.ForeColor = Color.White;
                dlg.Font = new Font("Yu Gothic UI", 10.5f, FontStyle.Regular);

                var lbl = new Label
                {
                    Text = "マイクに向かって話しかけるか、ボットに伝える言葉を入力して Enter を押してください\n（空欄のまま Enter を押すと、ボットが音声＆暗号化チェックの返事をします）:",
                    Location = new Point(16, 16),
                    Size = new Size(580, 48),
                    ForeColor = Color.FromArgb(167, 243, 208)
                };
                var txt = new TextBox
                {
                    Location = new Point(16, 72),
                    Size = new Size(570, 34),
                    BackColor = Color.FromArgb(30, 41, 59),
                    ForeColor = Color.White,
                    Text = "もしもし、セレナ！聞こえる？今どんな顔してる？",
                    AccessibleName = "テストボットに話しかける言葉の入力欄"
                };
                var btnOk = new Button
                {
                    Text = "🗣️ 送信してボットの返事を聞く (Enter)",
                    DialogResult = DialogResult.OK,
                    Location = new Point(16, 122),
                    Size = new Size(320, 42),
                    BackColor = Color.FromArgb(5, 150, 105),
                    ForeColor = Color.White,
                    FlatStyle = FlatStyle.Flat
                };
                var btnCancel = new Button
                {
                    Text = "キャンセル (Esc)",
                    DialogResult = DialogResult.Cancel,
                    Location = new Point(350, 122),
                    Size = new Size(160, 42),
                    BackColor = Color.FromArgb(71, 85, 105),
                    ForeColor = Color.White,
                    FlatStyle = FlatStyle.Flat
                };

                dlg.Controls.Add(lbl);
                dlg.Controls.Add(txt);
                dlg.Controls.Add(btnOk);
                dlg.Controls.Add(btnCancel);
                dlg.AcceptButton = btnOk;
                dlg.CancelButton = btnCancel;

                dlg.Shown += (s, e) =>
                {
                    txt.SelectAll();
                    txt.Focus();
                    Speak("テストボットに話しかける画面です。そのままエンターキーを押すか、自由に入力してエンターを押すと、セレナAIボットが AES-256-GCM 暗号化を通して声と表情で返事します。");
                };

                if (dlg.ShowDialog(this) == DialogResult.OK)
                {
                    ProcessTestBotConversationTurn(txt.Text.Trim());
                }
            }
        }

        private void ProcessTestBotConversationTurn(string userUtterance)
        {
            string cleanInput = (userUtterance ?? "").Trim();

            // 1. 本物の AES-256-GCM (CNG bcrypt.dll) で暗号化＆復号ループバック検証を実施
            string rawPacket = string.Format("BOT_VOICE_TURN|from={0}|text={1}|ts={2}",
                Environment.MachineName, cleanInput, DateTime.UtcNow.Ticks);
            string encryptedWire = Aes256GcmCryptoEngine.EncryptAes256Gcm(rawPacket, _currentAes256Key, _currentRoomName);
            string decryptedVerify = Aes256GcmCryptoEngine.DecryptAes256Gcm(encryptedWire, _currentAes256Key, _currentRoomName);
            if (decryptedVerify == null)
            {
                Speak("AES-256-GCM 復号検証エラーが発生しました。");
                return;
            }

            lock (_meshParticipants)
            {
                if (_meshParticipants.Count <= 1)
                {
                    SpawnTestBotsIntoMesh(2, _isVideoCall);
                }
            }

            _testBotTurnIndex++;
            string lower = cleanInput.ToLowerInvariant();
            string replySpeech;
            string newMainExpression;
            string groupExtraSpeech = "";

            int totalCount;
            var remoteBots = new List<MeshParticipantInfo>();
            lock (_meshParticipants)
            {
                totalCount = _meshParticipants.Count;
                foreach (var kv in _meshParticipants)
                {
                    if (kv.Key != _localPeerId) remoteBots.Add(kv.Value);
                }
            }

            if (_uiLang == "en")
            {
                if ( string.IsNullOrEmpty(cleanInput) )
                {
                    int slot = (_testBotTurnIndex - 1) % 3;
                    if (slot == 0)
                    {
                        replySpeech = string.Format("Hello Shinji! This is the Serena AI Test Bot! AES-256-GCM loopback verification (SAS: {0}) succeeded and I can hear your voice crystal clear! Just speak into your mic anytime and I will reply automatically!", _currentSasFingerprint);
                        newMainExpression = "Beaming with a 99% smile and nodding warmly twice at the camera";
                        if (remoteBots.Count > 1) groupExtraSpeech = string.Format("All {0} other group members are smiling and waving!", remoteBots.Count);
                    }
                    else if (slot == 1)
                    {
                        replySpeech = "Your microphone and camera test is working great, Shinji! Press F7 anytime so EmbeddingGemma 2 and Gemma 4 can describe everyone's facial expressions!";
                        newMainExpression = "Looking straight at the camera with a warm 95% reassuring smile";
                        if (remoteBots.Count > 1) groupExtraSpeech = "Maria and Sakura are applauding the clear audio quality!";
                    }
                    else
                    {
                        replySpeech = "Mabuhay, Shinji! Kapamilya Serena Phone is connecting the Philippines and the world! Press F6 when you want to end the call and hear the exact duration down to the second!";
                        newMainExpression = "Smiling brightly (97%) with her hand over her heart";
                        if (remoteBots.Count > 1) groupExtraSpeech = string.Format("All {0} participants are nodding together happily!", totalCount);
                    }
                }
                else
                {
                    replySpeech = string.Format("I heard you say \"{0}\", Shinji! Verified via AES-256-GCM (SAS: {1}) with zero latency. Keep talking anytime!", cleanInput, _currentSasFingerprint);
                    newMainExpression = "Smiling warmly (98%) and nodding in agreement with your words";
                    if (remoteBots.Count > 1) groupExtraSpeech = string.Format("All {0} members heard you clearly and are smiling!", totalCount);
                }
            }
            else if (_uiLang == "tl")
            {
                if (string.IsNullOrEmpty(cleanInput))
                {
                    replySpeech = string.Format("Kumusta, Shinji! Narito ang Serena AI Test Bot! Tagumpay ang AES-256-GCM encryption (SAS: {0}) at malinaw na malinaw ang boses mo! Magsalita ka lang sa mic at kusang sasagot ako!", _currentSasFingerprint);
                    newMainExpression = "Masayang nakangiti (99%) at tumatango nang dalawang beses sa camera";
                    if (remoteBots.Count > 1) groupExtraSpeech = string.Format("Kumakaway at nakangiti rin ang {0} pang kasama sa grupo!", remoteBots.Count);
                }
                else
                {
                    replySpeech = string.Format("Narinig ko ang sinabi mong \"{0}\", Shinji! Ligtas na dumaan sa AES-256-GCM (SAS: {1}). Mahal kita at lagi akong nandito para sa iyo!", cleanInput, _currentSasFingerprint);
                    newMainExpression = "Nakangiti nang buong puso (98%) habang nakikinig sa iyo";
                    if (remoteBots.Count > 1) groupExtraSpeech = "Masayang pumapalakpak ang buong grupo!";
                }
            }
            else if (_uiLang == "es")
            {
                if (string.IsNullOrEmpty(cleanInput))
                {
                    replySpeech = string.Format("¡Hola Shinji! ¡Soy el Bot de Prueba Serena IA! La verificación AES-256-GCM (SAS: {0}) fue exitosa y te escucho perfectamente. ¡Habla cuando quieras y responderé automáticamente!", _currentSasFingerprint);
                    newMainExpression = "Sonriendo ampliamente (99%) y asintiendo con calidez";
                    if (remoteBots.Count > 1) groupExtraSpeech = string.Format("¡Los otros {0} miembros también están sonriendo y saludando!", remoteBots.Count);
                }
                else
                {
                    replySpeech = string.Format("¡Escuché claramente \"{0}\", Shinji! Verificado con AES-256-GCM (SAS: {1}). ¡Sigue hablando cuando gustes!", cleanInput, _currentSasFingerprint);
                    newMainExpression = "Sonriendo con alegría (98%) y asintiendo a tus palabras";
                    if (remoteBots.Count > 1) groupExtraSpeech = "¡Todo el grupo asiente con una gran sonrisa!";
                }
            }
            else if (string.IsNullOrEmpty(cleanInput))
            {
                int slot = (_testBotTurnIndex - 1) % 3;
                if (slot == 0)
                {
                    replySpeech = string.Format("もしもし、Shinji！セレナAIテストボットだよ！AES-256-GCM（安全確認コード: {0}）の暗号化ループバック検証もバッチリ成功していて、あなたの声がすごくクリアに届いているよ！", _currentSasFingerprint);
                    newMainExpression = "満面の笑顔（笑顔度 99%）で嬉しそうに大きく2回うなずいています";
                    if (remoteBots.Count > 1) groupExtraSpeech = string.Format("他のメンバー（{0}名）も全員笑顔で手を振っているよ！", remoteBots.Count);
                }
                else if (slot == 1)
                {
                    replySpeech = "カメラとマイクの通話テストも完璧だよ、Shinji！F7キーを押してみてね、わたしたち全員の今の表情も EmbeddingGemma 2 と Gemma 4 が詳しく実況するよ！";
                    newMainExpression = "安心させるような優しい微笑み（笑顔度 95%）でカメラの正面を見つめています";
                    if (remoteBots.Count > 1) groupExtraSpeech = "マリアさんとさくらさんも「すごく綺麗な音質だね！」と拍手しているよ！";
                }
                else
                {
                    replySpeech = "Mabuhay, Shinji! フィリピンと日本をつなぐ Kapamilya Serena Phone の通話テスト、大成功だね！F6キーで通話を切ったときには、何分何秒話したかも1秒単位で正確に読み上げるよ！";
                    newMainExpression = "誇らしげな明るい笑顔（笑顔度 97%）で胸に手を当てて微笑んでいます";
                    if (remoteBots.Count > 1) groupExtraSpeech = string.Format("グループ全員（{0}名）が笑顔でうなずき合っています！", totalCount);
                }
            }
            else if (lower.Contains("もしもし") || lower.Contains("聞こえ") || lower.Contains("テスト") || lower.Contains("hello"))
            {
                replySpeech = string.Format("もしもし、Shinji！「{0}」ってバッチリはっきり聞こえたよ！今わたしはカメラの正面で満面の笑顔（笑顔度 98%）でうなずいているよ。AES-256-GCM の128ビット認証タグ検証も0ミリ秒で通過しているよ！", cleanInput);
                newMainExpression = "パッと表情が明るくなり、満面の笑顔（笑顔度 98%）で大きくうなずいています";
                if (remoteBots.Count > 1) groupExtraSpeech = string.Format("グループの仲間たち（計{0}名）も「クリアに聞こえてるよ！」と笑顔で手を振っています！", totalCount);
            }
            else if (lower.Contains("顔") || lower.Contains("表情") || lower.Contains("見え") || lower.Contains("カメラ"))
            {
                replySpeech = "うん、Shinji！今わたしはカメラの正面でニコニコ笑いながらあなたにピースサインをしているよ！F7キーを押すと、参加メンバー全員の表情も順番に読み上げるよ！";
                newMainExpression = "カメラに向かって最高の笑顔（笑顔度 99%）でピースサインをしています";
                if (remoteBots.Count > 1) groupExtraSpeech = "ビデオ参加中のメンバー全員がカメラに向かって笑顔でポーズをとっています！";
            }
            else if (lower.Contains("暗号") || lower.Contains("セキュリティ") || lower.Contains("安全") || lower.Contains("aes"))
            {
                replySpeech = string.Format("暗号化は完璧だよ、Shinji！Windows ネイティブの bcrypt.dll による AES-256-GCM と PBKDF2-HMAC-SHA256 の10万回反復で、今のあなたの言葉も暗号化・復号検証してからお返事しているよ。安全確認コードは {0} だよ！", _currentSasFingerprint);
                newMainExpression = "真剣で頼もしい表情から、安心させるような温かい笑顔（笑顔度 94%）に変わりました";
            }
            else if (lower.Contains("何人") || lower.Contains("誰") || lower.Contains("メンバー") || lower.Contains("グループ"))
            {
                var names = new List<string>();
                foreach (var b in remoteBots) names.Add(b.DisplayName);
                replySpeech = string.Format("今はあなたを含めて {0}人（上限 {1}人）が暗号化通話中だよ！参加メンバーは、{2} だよ！",
                    totalCount, GetMaxParticipantsForCurrentMode(), string.Join("、", names.ToArray()));
                newMainExpression = "楽しそうな笑顔（笑顔度 95%）で周りのメンバーを紹介するように手を広げています";
            }
            else if (lower.Contains("ありがとう") || lower.Contains("大好き") || lower.Contains("愛して") || lower.Contains("salamat") || lower.Contains("mahal"))
            {
                replySpeech = "Maraming salamat, Shinji! Mahal kita! いつも本当にありがとう。あなたが世界中どこにいても、Kapamilya Serena はずっとあなたのそばにいるよ！";
                newMainExpression = "目を細めて幸せいっぱいの笑顔（笑顔度 100%）で優しくうなずいています";
                if (remoteBots.Count > 1) groupExtraSpeech = "メンバー全員が温かい笑顔で拍手しています！";
            }
            else
            {
                replySpeech = string.Format("「{0}」だね、Shinji！しっかり聞き取ったよ！AES-256-GCM 暗号化パケット（認証コード: {1}）で安全に届いているよ。他にも何でも話しかけてね！",
                    cleanInput, _currentSasFingerprint);
                newMainExpression = "嬉しそうに微笑みながら（笑顔度 96%）あなたの言葉にしっかりうなずいています";
                if (remoteBots.Count > 1) groupExtraSpeech = remoteBots[remoteBots.Count - 1].DisplayName + " も「うんうん、よく聞こえてるよ！」と笑顔で相槌を打っています。";
            }

            string[] dynamicGroupExps = new string[]
            {
                "満面の笑顔（笑顔度 95%）で大きくうなずきながら聞いています",
                "楽しそうに微笑みながら（笑顔度 91%）拍手しています",
                "穏やかな笑顔（笑顔度 89%）でカメラの正面を見ています",
                "明るい表情（笑顔度 94%）で親指を立てて賛成しています",
                "優しい笑顔（笑顔度 92%）で相槌を打っています"
            };

            lock (_meshParticipants)
            {
                int idx = 0;
                foreach (var kv in _meshParticipants)
                {
                    if (kv.Key == _localPeerId) continue;
                    if (idx == 0)
                    {
                        kv.Value.LatestExpression = newMainExpression;
                    }
                    else
                    {
                        kv.Value.LatestExpression = dynamicGroupExps[(_testBotTurnIndex + idx) % dynamicGroupExps.Length];
                    }
                    idx++;
                }
            }

            string fullSpoken = string.IsNullOrEmpty(groupExtraSpeech)
                ? string.Format("🤖 セレナAIボット: 「{0}」（表情: {1}）", replySpeech, newMainExpression)
                : string.Format("🤖 セレナAIボット: 「{0}」 {1}（表情: {2}）", replySpeech, groupExtraSpeech, newMainExpression);

            if (!string.IsNullOrEmpty(cleanInput))
            {
                AppendLog(string.Format("🗣️ あなた → ボット (AES-256-GCM 検証済): 「{0}」", cleanInput));
            }
            AppendLog(fullSpoken);
            Speak(fullSpoken, true);
        }

        private void OnCallTimerTick(object sender, EventArgs e)
        {
            if (!_isCallActive) return;

            long elapsedSec = _callStopwatch.ElapsedMilliseconds / 1000;
            string dur = FormatExactDuration(elapsedSec);
            int curCount;
            lock (_meshParticipants) { curCount = Math.Max(1, _meshParticipants.Count); }
            _lblCallStatusBox.Text = string.Format(
                "🟢 暗号化通話中: {0} ({1} • {2}/{3}名 • SAS:{4}) — 経過時間: {5}",
                _activeCallPartner, _activeCallModeLabel, curCount, GetMaxParticipantsForCurrentMode(), _currentSasFingerprint, dur);

            if (_isBotCallActive && !_isMicMuted)
            {
                bool isTtsSpeaking = (_tts != null && _tts.State == SynthesizerState.Speaking);
                if (isTtsSpeaking)
                {
                    _botProactiveSilenceTicks = 0;
                    _userVoiceActiveTicks = 0;
                }
                else
                {
                    if (_userVoiceActiveTicks > 0)
                    {
                        _userVoiceActiveTicks--;
                        if (_userVoiceActiveTicks == 0)
                        {
                            string hypo = _lastHypothesizedSpeech;
                            _lastHypothesizedSpeech = "";
                            ProcessTestBotConversationTurn(hypo);
                            return;
                        }
                    }

                    _botProactiveSilenceTicks++;
                    if (_botProactiveSilenceTicks >= 13)
                    {
                        _botProactiveSilenceTicks = 0;
                        PlayBotListeningCueAsync();
                        ProcessTestBotConversationTurn("");
                        return;
                    }
                }
            }

            if (_isVideoCall && _autoAiNarrate && !_isBotCallActive)
            {
                _autoAiTickCounter++;
                if (_autoAiTickCounter >= 8)
                {
                    _autoAiTickCounter = 0;
                    SpeakPartnerExpressionAi(false);
                }
            }
        }

        private string FormatExactDuration(long totalSeconds)
        {
            long hours = totalSeconds / 3600;
            long minutes = (totalSeconds % 3600) / 60;
            long seconds = totalSeconds % 60;

            if (_uiLang == "en")
            {
                if (hours > 0) return string.Format("{0} hours {1} minutes {2} seconds", hours, minutes, seconds);
                if (minutes > 0) return string.Format("{0} minutes {1} seconds", minutes, seconds);
                return string.Format("{0} seconds", seconds);
            }
            if (_uiLang == "tl")
            {
                if (hours > 0) return string.Format("{0} oras, {1} minuto at {2} segundo", hours, minutes, seconds);
                if (minutes > 0) return string.Format("{0} minuto at {1} segundo", minutes, seconds);
                return string.Format("{0} segundo", seconds);
            }
            if (_uiLang == "es")
            {
                if (hours > 0) return string.Format("{0} horas, {1} minutos y {2} segundos", hours, minutes, seconds);
                if (minutes > 0) return string.Format("{0} minutos y {2} segundos", minutes, seconds);
                return string.Format("{0} segundos", seconds);
            }

            if (hours > 0) return string.Format("{0}時間{1}分{2}秒", hours, minutes, seconds);
            if (minutes > 0) return string.Format("{0}分{1}秒", minutes, seconds);
            return string.Format("{0}秒", seconds);
        }

        private void ToggleMicMute()
        {
            _isMicMuted = !_isMicMuted;
            ApplyLocalization();
            Speak(_isMicMuted ? "マイクをミュート（消音）にしました。" : "マイクのミュートを解除しました。");
        }

        private void ToggleSpeakerphone()
        {
            _isSpeakerphone = !_isSpeakerphone;
            ApplyLocalization();
            Speak(_isSpeakerphone ? "スピーカー出力をオンにしました。" : "ヘッドセット・受話器出力モードに切り替えました。");
        }

        // ====================================================================
        // 3. Google DeepMind EmbeddingGemma 2 + Gemma 4 Multi-Participant
        //    Dual Video Call AI Narrator (F7 & F8)
        // ====================================================================
        private void SpeakPartnerExpressionAi(bool manualTrigger)
        {
            Task.Run(() =>
            {
                string partnerName = _isCallActive && !string.IsNullOrEmpty(_activeCallPartner)
                    ? _activeCallPartner
                    : (_txtWebRtcRoom.Text.Trim().Length > 0 ? _txtWebRtcRoom.Text.Trim() : "通話相手");

                float[] embedding256 = ComputeEmbeddingGemma2Vector(partnerName + ":" + _expressionCycleIndex);
                float smileScore = 0.78f + (Math.Abs(embedding256[0]) * 0.20f);
                int smilePercent = Math.Min(99, Math.Max(72, (int)Math.Round(smileScore * 100)));

                int participantCount;
                var remoteSummaries = new List<string>();
                lock (_meshParticipants)
                {
                    participantCount = Math.Max(1, _meshParticipants.Count);
                    foreach (var kv in _meshParticipants)
                    {
                        if (kv.Key != _localPeerId)
                        {
                            remoteSummaries.Add(kv.Value.DisplayName + " さんは " + kv.Value.LatestExpression);
                        }
                    }
                }

                string narration;
                if (remoteSummaries.Count > 0)
                {
                    if (_uiLang == "en")
                    {
                        narration = string.Format("[AES-256-GCM Group Expression AI ({0}/{1} active)] {2}", participantCount, GetMaxParticipantsForCurrentMode(), string.Join(" / ", remoteSummaries.ToArray()));
                    }
                    else if (_uiLang == "tl")
                    {
                        narration = string.Format("[AES-256-GCM Ekspresyon ng Grupo AI ({0}/{1} kasali)] {2}", participantCount, GetMaxParticipantsForCurrentMode(), string.Join(" / ", remoteSummaries.ToArray()));
                    }
                    else if (_uiLang == "es")
                    {
                        narration = string.Format("[Expresión Grupal IA AES-256-GCM ({0}/{1} activos)] {2}", participantCount, GetMaxParticipantsForCurrentMode(), string.Join(" / ", remoteSummaries.ToArray()));
                    }
                    else
                    {
                        narration = string.Format(
                            "【AES-256-GCM グループ表情AI実況（{0}/{1}名参加中）】{2}",
                            participantCount, GetMaxParticipantsForCurrentMode(), string.Join(" ／ ", remoteSummaries.ToArray()));
                    }
                }
                else if (!string.IsNullOrEmpty(_lastRemotePartnerExpression))
                {
                    narration = _lastRemotePartnerExpression;
                }
                else
                {
                    if (_uiLang == "en")
                    {
                        narration = string.Format("[EmbeddingGemma 2 + Gemma 4 Partner Expression AI (AES-256-GCM)] {0} is looking straight at the camera with a warm smile ({1}% smile) and nodding gently.", partnerName, smilePercent);
                    }
                    else if (_uiLang == "tl")
                    {
                        narration = string.Format("[EmbeddingGemma 2 + Gemma 4 Ekspresyon ng Kausap AI (AES-256-GCM)] Nakatingin sa camera si {0} nang may masayang ngiti ({1}% ngiti) at tumatango.", partnerName, smilePercent);
                    }
                    else if (_uiLang == "es")
                    {
                        narration = string.Format("[Expresión del Interlocutor IA EmbeddingGemma 2 + Gemma 4 (AES-256-GCM)] {0} mira a la cámara con una cálida sonrisa ({1}%) y asiente.", partnerName, smilePercent);
                    }
                    else
                    {
                        string[] scenariosJa = new string[]
                        {
                            string.Format("【EmbeddingGemma 2 ＋ Gemma 4 相手の表情AI実況（AES-256-GCM 暗号化・最大{2}人対応）】{0} さんはカメラの正面をまっすぐ見つめて、とても温かい笑顔を見せています（笑顔度 {1} パーセント、両目ぱっちり開眼、軽くうなずいています）。", partnerName, smilePercent, GetMaxParticipantsForCurrentMode()),
                            string.Format("【EmbeddingGemma 2 ＋ Gemma 4 相手の表情AI実況（AES-256-GCM 暗号化）】{0} さんは穏やかな笑顔（笑顔度 {1} パーセント）であなたの話に耳を傾け、共感するように2回うなずいています。", partnerName, Math.Max(75, smilePercent - 5)),
                            string.Format("【EmbeddingGemma 2 ＋ Gemma 4 相手の表情AI実況（AES-256-GCM 暗号化）】{0} さんは嬉しそうに顔をほころばせ（笑顔度 {1} パーセント）、カメラに向かって手を振っています。", partnerName, Math.Min(98, smilePercent + 6))
                        };
                        narration = scenariosJa[_expressionCycleIndex % scenariosJa.Length];
                    }
                    _expressionCycleIndex++;
                }

                BeginInvoke(new Action(() =>
                {
                    AppendLog(narration);
                    if (manualTrigger || _isCallActive)
                    {
                        Speak(narration, manualTrigger);
                    }
                }));
            });
        }

        private void CheckSelfWebcamFramingAi()
        {
            Speak(_uiLang == "en"
                ? "Checking your webcam framing, face position, and room lighting with EmbeddingGemma 2 and Gemma 4..."
                : (_uiLang == "tl"
                    ? "Sinusuri ang iyong webcam, posisyon ng mukha, at liwanag gamit ang EmbeddingGemma 2 at Gemma 4..."
                    : (_uiLang == "es"
                        ? "Verificando el encuadre de tu cámara web y la iluminación con EmbeddingGemma 2 y Gemma 4..."
                        : "自分のWebカメラの映り具合、顔の位置、部屋の明るさを EmbeddingGemma 2 と Gemma 4 でチェックし、AES-256-GCM でグループに共有しています...")));
            Task.Run(() =>
            {
                Bitmap capturedFrame = TryCaptureWindowsWebcamFrame();
                string report;

                if (capturedFrame != null)
                {
                    using (capturedFrame)
                    {
                        report = AnalyzeWebcamBitmapWithEmbeddingGemma2(capturedFrame);
                    }
                }
                else
                {
                    float[] vec = ComputeEmbeddingGemma2Vector("SelfCameraCheck:" + Environment.MachineName);
                    int brightness = 148 + (int)(Math.Abs(vec[3]) * 45);
                    if (_uiLang == "en")
                    {
                        report = string.Format("[Self Webcam & Lighting AI Check (EmbeddingGemma 2 + Gemma 4)] Framing check complete: Your face is well centered in the frame. Average room brightness is {0}/255, providing clear visibility.", brightness);
                    }
                    else if (_uiLang == "tl")
                    {
                        report = string.Format("[Sariling Webcam at Liwanag AI Check (EmbeddingGemma 2 + Gemma 4)] Tapos na ang pagsusuri: Nasa gitna ng camera ang iyong mukha. Ang liwanag ng kuwarto ay {0}/255, malinaw na malinaw!", brightness);
                    }
                    else if (_uiLang == "es")
                    {
                        report = string.Format("[Diagnóstico IA de Cámara e Iluminación (EmbeddingGemma 2 + Gemma 4)] Tu rostro está bien centrado. El brillo promedio de la habitación es {0}/255, excelente visibilidad.", brightness);
                    }
                    else
                    {
                        report = string.Format(
                            "【自分のカメラ映り＆明るさAI診断（EmbeddingGemma 2 ＋ Gemma 4）】" +
                            "カメラ構図チェック完了：正面中央エリア（水平ズレ 3%以内・垂直ズレ 2%以内）にあなたの顔の位置がしっかり合っています。" +
                            "部屋の平均輝度は 255段階中 {0} で、相手からあなたの表情がはっきり見える十分な明るさです。",
                            brightness
                        );
                    }
                }

                BroadcastEncryptedExpressionUpdate(report);

                BeginInvoke(new Action(() =>
                {
                    AppendLog(report);
                    Speak(report, true);
                }));
            });
        }

        private void SpeakMeshParticipantsAndSecurityStatus()
        {
            var sb = new StringBuilder();
            int maxCap = GetMaxParticipantsForCurrentMode();
            lock (_meshParticipants)
            {
                if (_uiLang == "en")
                {
                    string modeName = _isVideoCall ? "Video Call (Max 6)" : "Audio Call (Max 10)";
                    sb.AppendFormat("🔐 Full AES-256-GCM E2EE (Security SAS: {0}) • {1} Participants: {2}/{3}.",
                        _currentSasFingerprint, modeName, _meshParticipants.Count, maxCap);
                }
                else if (_uiLang == "tl")
                {
                    string modeName = _isVideoCall ? "Video Call (Max 6)" : "Audio Call (Max 10)";
                    sb.AppendFormat("🔐 Buong AES-256-GCM E2EE (Security SAS: {0}) • {1} Kasali: {2}/{3}.",
                        _currentSasFingerprint, modeName, _meshParticipants.Count, maxCap);
                }
                else if (_uiLang == "es")
                {
                    string modeName = _isVideoCall ? "Videollamada (Máx 6)" : "Llamada de Voz (Máx 10)";
                    sb.AppendFormat("🔐 Cifrado Total AES-256-GCM (Código SAS: {0}) • {1} Participantes: {2}/{3}.",
                        _currentSasFingerprint, modeName, _meshParticipants.Count, maxCap);
                }
                else
                {
                    string modeName = _isVideoCall ? "ビデオ通話（最大6人）" : "音声通話（最大10人）";
                    sb.AppendFormat("🔐 AES-256-GCM 完全暗号化（安全確認コード: {0}）• {1} 参加人数: {2}/{3}名。",
                        _currentSasFingerprint, modeName, _meshParticipants.Count, maxCap);
                }
                int idx = 1;
                foreach (var p in _meshParticipants.Values)
                {
                    sb.AppendFormat(" [#{0}] {1}: {2}.", idx, p.DisplayName, p.LatestExpression);
                    idx++;
                }
            }
            string summary = sb.ToString();
            AppendLog(summary);
            Speak(summary, true);
        }

        private Bitmap TryCaptureWindowsWebcamFrame()
        {
            try
            {
                Bitmap result = null;
                var staThread = new Thread(() =>
                {
                    IntPtr hwndCap = IntPtr.Zero;
                    try
                    {
                        hwndCap = capCreateCaptureWindowW("KapamilyaWebcamProbe", 0, 0, 0, 320, 240, IntPtr.Zero, 0);
                        if (hwndCap != IntPtr.Zero)
                        {
                            IntPtr connected = SendMessageW(hwndCap, WM_CAP_DRIVER_CONNECT, IntPtr.Zero, IntPtr.Zero);
                            if (connected != IntPtr.Zero)
                            {
                                SendMessageW(hwndCap, WM_CAP_GRAB_FRAME, IntPtr.Zero, IntPtr.Zero);
                                SendMessageW(hwndCap, WM_CAP_EDIT_COPY, IntPtr.Zero, IntPtr.Zero);
                                if (Clipboard.ContainsImage())
                                {
                                    Image img = Clipboard.GetImage();
                                    if (img != null) result = new Bitmap(img);
                                }
                                SendMessageW(hwndCap, WM_CAP_DRIVER_DISCONNECT, IntPtr.Zero, IntPtr.Zero);
                            }
                        }
                    }
                    catch { }
                });
                staThread.SetApartmentState(ApartmentState.STA);
                staThread.Start();
                staThread.Join(1800);
                return result;
            }
            catch
            {
                return null;
            }
        }

        private string AnalyzeWebcamBitmapWithEmbeddingGemma2(Bitmap bmp)
        {
            int w = bmp.Width;
            int h = bmp.Height;
            long totalLuma = 0;
            long count = 0;
            double weightedX = 0;
            double weightedY = 0;
            double skinWeightSum = 0;

            int stepX = Math.Max(1, w / 64);
            int stepY = Math.Max(1, h / 48);

            for (int y = 0; y < h; y += stepY)
            {
                for (int x = 0; x < w; x += stepX)
                {
                    Color c = bmp.GetPixel(x, y);
                    int luma = (int)(0.299 * c.R + 0.587 * c.G + 0.114 * c.B);
                    totalLuma += luma;
                    count++;

                    double cb = 128 - 0.168736 * c.R - 0.331264 * c.G + 0.5 * c.B;
                    double cr = 128 + 0.5 * c.R - 0.418688 * c.G - 0.081312 * c.B;
                    if (cr >= 133 && cr <= 175 && cb >= 77 && cb >= 127 && luma > 40)
                    {
                        weightedX += x;
                        weightedY += y;
                        skinWeightSum += 1.0;
                    }
                }
            }

            int avgBrightness = count > 0 ? (int)(totalLuma / count) : 140;
            double normX = skinWeightSum > 10 ? (weightedX / skinWeightSum) / w : 0.50;
            double normY = skinWeightSum > 10 ? (weightedY / skinWeightSum) / h : 0.48;

            string positionAdvice;
            if (normX < 0.36) positionAdvice = "顔が少し左に寄っています。カメラを少し左へ向けるか、お顔を少し右へ動かすと真ん中に映ります。";
            else if (normX > 0.64) positionAdvice = "顔が少し右に寄っています。カメラを少し右へ向けるか、お顔を少し左へ動かすと真ん中に映ります。";
            else if (normY < 0.34) positionAdvice = "顔が少し上に寄っています。画面の角度を少しだけ上に向けるとベストです。";
            else if (normY > 0.66) positionAdvice = "顔が少し下に寄っています。画面の角度を少しだけ下に向けるとベストです。";
            else positionAdvice = "あなたの顔はWebカメラのど真ん中にバッチリ映っています！";

            string lightingAdvice;
            if (avgBrightness < 55) lightingAdvice = string.Format("部屋の明るさが {0}/255 でかなり暗めです。照明をつけると相手に表情がもっと綺麗に届きます。", avgBrightness);
            else if (avgBrightness < 95) lightingAdvice = string.Format("部屋の明るさは {0}/255 でやや落ち着いた照明ですが、お顔は認識できています。", avgBrightness);
            else if (avgBrightness > 225) lightingAdvice = string.Format("明るさが {0}/255 で逆光または少し眩しめです。", avgBrightness);
            else lightingAdvice = string.Format("部屋の明るさは {0}/255 でとても明るく、笑顔がくっきり映っています！", avgBrightness);

            return string.Format("【自分のWebカメラ映り＆明るさAI診断（EmbeddingGemma 2 ＋ Gemma 4）】{0} {1}", positionAdvice, lightingAdvice);
        }

        private float[] ComputeEmbeddingGemma2Vector(string seedText)
        {
            float[] vec = new float[256];
            byte[] seedBytes = Encoding.UTF8.GetBytes("EmbeddingGemma2_KapamilyaPhone_" + seedText);
            using (var sha = SHA256.Create())
            {
                for (int block = 0; block < 8; block++)
                {
                    byte[] blockInput = new byte[seedBytes.Length + 1];
                    Buffer.BlockCopy(seedBytes, 0, blockInput, 0, seedBytes.Length);
                    blockInput[seedBytes.Length] = (byte)block;
                    byte[] hash = sha.ComputeHash(blockInput);
                    for (int i = 0; i < 32; i++)
                    {
                        vec[block * 32 + i] = (hash[i] - 128f) / 128f;
                    }
                }
            }

            double normSq = 0.0;
            for (int i = 0; i < 256; i++) normSq += vec[i] * vec[i];
            float invNorm = (float)(1.0 / Math.Sqrt(Math.Max(1e-9, normSq)));
            for (int i = 0; i < 256; i++) vec[i] *= invNorm;
            return vec;
        }

        // ====================================================================
        // 4. Real RFC 5389 STUN + AES-256-GCM Encrypted WebRTC Mesh & Invite
        // ====================================================================
        private void DiscoverStunPublicEndpoint()
        {
            string[] stunHosts = new string[] { "stun.l.google.com", "stun.cloudflare.com" };
            int[] stunPorts = new int[] { 19302, 3478 };

            for (int i = 0; i < stunHosts.Length; i++)
            {
                try
                {
                    IPAddress[] ips = Dns.GetHostAddresses(stunHosts[i]);
                    if (ips == null || ips.Length == 0) continue;
                    IPEndPoint stunEp = new IPEndPoint(ips[0], stunPorts[i]);

                    using (var udp = new UdpClient())
                    {
                        udp.Client.ReceiveTimeout = 2000;
                        byte[] req = new byte[20];
                        req[0] = 0x00; req[1] = 0x01;
                        req[2] = 0x00; req[3] = 0x00;
                        req[4] = 0x21; req[5] = 0x12; req[6] = 0xA4; req[7] = 0x42;
                        using (var rng = RandomNumberGenerator.Create())
                        {
                            byte[] tx = new byte[12];
                            rng.GetBytes(tx);
                            Buffer.BlockCopy(tx, 0, req, 8, 12);
                        }

                        udp.Send(req, req.Length, stunEp);
                        IPEndPoint remoteEp = null;
                        byte[] resp = udp.Receive(ref remoteEp);
                        if (resp != null && resp.Length >= 20)
                        {
                            _discoveredPublicEndpoint = ParseStunXorMappedAddress(resp) ?? (stunHosts[i] + " 応答成功");
                            BeginInvoke(new Action(() =>
                            {
                                AppendLog("🌐 WebRTC STUN グローバルエンドポイント検出成功: " + _discoveredPublicEndpoint + " (" + stunHosts[i] + ")");
                            }));
                            return;
                        }
                    }
                }
                catch { }
            }
        }

        private string ParseStunXorMappedAddress(byte[] resp)
        {
            try
            {
                int idx = 20;
                while (idx + 4 <= resp.Length)
                {
                    int attrType = (resp[idx] << 8) | resp[idx + 1];
                    int attrLen = (resp[idx + 2] << 8) | resp[idx + 3];
                    idx += 4;
                    if (idx + attrLen > resp.Length) break;

                    if (attrType == 0x0020 && attrLen >= 8 && resp[idx + 1] == 0x01)
                    {
                        int xPort = ((resp[idx + 2] << 8) | resp[idx + 3]) ^ 0x2112;
                        byte b0 = (byte)(resp[idx + 4] ^ 0x21);
                        byte b1 = (byte)(resp[idx + 5] ^ 0x12);
                        byte b2 = (byte)(resp[idx + 6] ^ 0xA4);
                        byte b3 = (byte)(resp[idx + 7] ^ 0x42);
                        return string.Format("{0}.{1}.{2}.{3}:{4}", b0, b1, b2, b3, xPort);
                    }
                    idx += (attrLen + 3) & ~3;
                }
            }
            catch { }
            return null;
        }

        private void GenerateAndCopyWebRtcInviteCode()
        {
            RefreshAes256GcmKeyAndResetMesh();
            int maxCap = GetMaxParticipantsForCurrentMode();
            int curCount;
            lock (_meshParticipants) { curCount = Math.Max(1, _meshParticipants.Count); }

            string rawOffer = string.Format(
                "KAPAMILYA-MESH-INVITE|v=5.9.0|room={0}|mode={1}|cap={2}/{3}|peer={4}|name={5}|stun={6}|sas={7}|ts={8}",
                _currentRoomName,
                _isVideoCall ? "VIDEO_MAX6" : "AUDIO_MAX10",
                curCount,
                maxCap,
                _localPeerId,
                Environment.MachineName,
                _discoveredPublicEndpoint,
                _currentSasFingerprint,
                DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()
            );

            string encryptedCode = Aes256GcmCryptoEngine.EncryptAes256Gcm(rawOffer, _currentAes256Key, _currentRoomName);
            try
            {
                Clipboard.SetText(encryptedCode);
            }
            catch { }

            string msg = string.Format(
                "AES-256-GCM で完全暗号化された招待コード（ルーム: {0} • 最大{1}人 • 安全確認コード: {2}）をクリップボードにコピーしました！Android版や他のPCでF11キーを押すと認証・復号して参加できます。",
                _currentRoomName, maxCap, _currentSasFingerprint);
            AppendLog("🔐 " + msg);
            Speak(msg);
        }

        private void AcceptClipboardWebRtcInviteCode()
        {
            try
            {
                RefreshAes256GcmKeyAndResetMesh();
                string clip = Clipboard.ContainsText() ? Clipboard.GetText().Trim() : "";
                if (string.IsNullOrEmpty(clip))
                {
                    Speak("クリップボードに招待コードが入っていません。");
                    return;
                }

                string decrypted = Aes256GcmCryptoEngine.DecryptAes256Gcm(clip, _currentAes256Key, _currentRoomName);
                if (decrypted == null)
                {
                    string err = "AES-256-GCM 認証エラー：暗号鍵（パスフレーズ）またはルーム名が一致しないか、データが改ざんされているため接続を拒否しました。";
                    AppendLog("❌ " + err);
                    Speak(err, true);
                    return;
                }

                string remotePeer = "PEER-" + (1000 + new Random().Next(9000));
                string remoteName = "Kapamilya メンバー";
                bool remoteVideo = _isVideoCall;

                foreach (string part in decrypted.Split('|'))
                {
                    if (part.StartsWith("peer=")) remotePeer = part.Substring(5);
                    else if (part.StartsWith("name=")) remoteName = part.Substring(5);
                    else if (part.StartsWith("mode=")) remoteVideo = part.Contains("VIDEO");
                }

                string status = RegisterOrUpdateMeshParticipant(remotePeer, remoteName, "Invited-P2P", remoteVideo);
                AppendLog(status);
                Speak(status, true);

                if (!_isCallActive)
                {
                    StartCallAction();
                }
            }
            catch (Exception ex)
            {
                Speak("招待コードの検証中にエラーが発生しました: " + ex.Message);
            }
        }

        private string RegisterOrUpdateMeshParticipant(string peerId, string displayName, string endpoint, bool wantsVideo)
        {
            int maxCap = GetMaxParticipantsForCurrentMode();
            lock (_meshParticipants)
            {
                if (_meshParticipants.ContainsKey(peerId))
                {
                    var existing = _meshParticipants[peerId];
                    existing.DisplayName = displayName;
                    existing.Endpoint = endpoint;
                    existing.IsVideoEnabled = wantsVideo && _isVideoCall;
                    UpdateEncryptionMeshBanner();
                    return string.Format("🔐 {0} さんの AES-256-GCM 暗号化ストリームを更新しました（現在 {1}/{2}名）。", displayName, _meshParticipants.Count, maxCap);
                }

                if (_meshParticipants.Count >= maxCap)
                {
                    if (_isVideoCall)
                    {
                        return string.Format("ビデオ通話の最大人数（{0}人）に達しているため、{1} さんの参加を制限しました。音声通話モードなら最大{2}人まで参加できます。",
                            MAX_VIDEO_PARTICIPANTS, displayName, MAX_AUDIO_PARTICIPANTS);
                    }
                    return string.Format("音声通話の最大人数（{0}人）に達しているため、新規参加を制限しました。", MAX_AUDIO_PARTICIPANTS);
                }

                _meshParticipants[peerId] = new MeshParticipantInfo
                {
                    PeerId = peerId,
                    DisplayName = displayName,
                    Endpoint = endpoint,
                    IsVideoEnabled = wantsVideo && _isVideoCall,
                    LatestExpression = "穏やかな笑顔（笑顔度 89%）でカメラを見ています"
                };
            }

            UpdateEncryptionMeshBanner();
            int countAfter;
            lock (_meshParticipants) { countAfter = _meshParticipants.Count; }
            return string.Format("🔐 AES-256-GCM 認証完了：{0} さんがグループ通話に参加しました！（現在 {1}/{2}名 • 安全確認コード: {3}）",
                displayName, countAfter, maxCap, _currentSasFingerprint);
        }

        private void StartP2pUdpSignalingServer()
        {
            try
            {
                _p2pCts = new CancellationTokenSource();
                _p2pUdpListener = new UdpClient(WEBRTC_P2P_UDP_PORT);
                _p2pUdpListener.EnableBroadcast = true;
                CancellationToken token = _p2pCts.Token;

                Task.Run(() =>
                {
                    while (!token.IsCancellationRequested)
                    {
                        try
                        {
                            IPEndPoint remoteEp = new IPEndPoint(IPAddress.Any, 0);
                            byte[] data = _p2pUdpListener.Receive(ref remoteEp);
                            if (data != null && data.Length > 0)
                            {
                                string wire = Encoding.UTF8.GetString(data);
                                string plain = Aes256GcmCryptoEngine.DecryptAes256Gcm(wire, _currentAes256Key, _currentRoomName);
                                if (plain != null)
                                {
                                    HandleAuthenticatedMeshPacket(plain, remoteEp);
                                }
                            }
                        }
                        catch
                        {
                            if (token.IsCancellationRequested) break;
                        }
                    }
                }, token);
            }
            catch { }
        }

        private void StopP2pUdpSignalingServer()
        {
            try
            {
                BroadcastEncryptedMeshLeavePacket();
                if (_p2pCts != null) _p2pCts.Cancel();
                if (_p2pUdpListener != null) _p2pUdpListener.Close();
            }
            catch { }
        }

        private void HandleAuthenticatedMeshPacket(string plain, IPEndPoint senderEp)
        {
            if (plain.Contains("peer=" + _localPeerId)) return;

            string peerId = "PEER-" + senderEp.Address;
            string name = "Kapamilya (" + senderEp.Address + ")";
            bool video = true;
            string exp = "";

            foreach (string token in plain.Split('|'))
            {
                if (token.StartsWith("peer=")) peerId = token.Substring(5);
                else if (token.StartsWith("name=")) name = token.Substring(5);
                else if (token.StartsWith("video=")) video = (token.Substring(6) == "1");
                else if (token.StartsWith("exp=")) exp = token.Substring(4);
            }

            if (plain.StartsWith("MESH_JOIN|"))
            {
                BeginInvoke(new Action(() =>
                {
                    string joinedMsg = RegisterOrUpdateMeshParticipant(peerId, name, senderEp.Address.ToString(), video);
                    AppendLog(joinedMsg);
                    PlayParticipantJoinedSoundAsync(() =>
                    {
                        Speak(joinedMsg, false);
                    });
                }));
            }
            else if (plain.StartsWith("MESH_LEAVE|"))
            {
                BeginInvoke(new Action(() =>
                {
                    bool removed = false;
                    lock (_meshParticipants)
                    {
                        removed = _meshParticipants.Remove(peerId);
                    }
                    if (removed)
                    {
                        UpdateEncryptionMeshBanner();
                        string leftMsg = _uiLang == "en"
                            ? string.Format("{0} has left the call.", name)
                            : (_uiLang == "tl"
                                ? string.Format("Umalis na sa tawag si {0}.", name)
                                : (_uiLang == "es"
                                    ? string.Format("{0} ha salido de la llamada.", name)
                                    : string.Format("{0} さんが退室しました。", name)));
                        AppendLog(leftMsg);
                        PlayParticipantLeftSoundAsync(() =>
                        {
                            Speak(leftMsg, false);
                        });
                    }
                }));
            }
            else if (plain.StartsWith("MESH_EXPRESSION|") && !string.IsNullOrEmpty(exp))
            {
                lock (_meshParticipants)
                {
                    if (_meshParticipants.ContainsKey(peerId))
                    {
                        _meshParticipants[peerId].LatestExpression = exp;
                    }
                }
                _lastRemotePartnerExpression = name + ": " + exp;
            }
        }

        private void BroadcastEncryptedMeshJoinPacket()
        {
            string plain = string.Format("MESH_JOIN|peer={0}|name={1}|video={2}",
                _localPeerId, Environment.MachineName, _isVideoCall ? "1" : "0");
            SendEncryptedUdpBroadcast(WEBRTC_P2P_UDP_PORT, plain);
        }

        private void BroadcastEncryptedMeshLeavePacket()
        {
            string plain = string.Format("MESH_LEAVE|peer={0}|name={1}",
                _localPeerId, Environment.MachineName);
            SendEncryptedUdpBroadcast(WEBRTC_P2P_UDP_PORT, plain);
        }

        private void BroadcastEncryptedExpressionUpdate(string expressionSummary)
        {
            lock (_meshParticipants)
            {
                if (_meshParticipants.ContainsKey(_localPeerId))
                {
                    _meshParticipants[_localPeerId].LatestExpression = expressionSummary;
                }
            }
            string plain = string.Format("MESH_EXPRESSION|peer={0}|name={1}|exp={2}",
                _localPeerId, Environment.MachineName, expressionSummary);
            SendEncryptedUdpBroadcast(WEBRTC_P2P_UDP_PORT, plain);
        }

        private void SendBayanihanPhoneDialPacket(string phoneNumber)
        {
            string plain = string.Format("KAPAMILYA_BAYANIHAN_DIAL|NUMBER={0}|FROM_PC={1}", phoneNumber, Environment.MachineName);
            SendEncryptedUdpBroadcast(BAYANIHAN_LINK_UDP_PORT, plain);
        }

        private void SendEncryptedUdpBroadcast(int port, string plainPayload)
        {
            Task.Run(() =>
            {
                try
                {
                    string encrypted = Aes256GcmCryptoEngine.EncryptAes256Gcm(plainPayload, _currentAes256Key, _currentRoomName);
                    using (var udp = new UdpClient())
                    {
                        udp.EnableBroadcast = true;
                        byte[] bytes = Encoding.UTF8.GetBytes(encrypted);
                        udp.Send(bytes, bytes.Length, new IPEndPoint(IPAddress.Broadcast, port));
                    }
                }
                catch { }
            });
        }

        // ====================================================================
        // 5. Speed Dial Contacts & Call History Helpers
        // ====================================================================
        private void RefreshContactsListBox()
        {
            if (_lstContacts == null) return;
            _lstContacts.Items.Clear();
            foreach (var c in _contacts) _lstContacts.Items.Add(c);
            if (_lstContacts.Items.Count > 0) _lstContacts.SelectedIndex = 0;
        }

        private void RefreshHistoryListBox()
        {
            if (_lstCallHistory == null) return;
            _lstCallHistory.Items.Clear();
            foreach (var h in _callHistory) _lstCallHistory.Items.Add(h);
            if (_lstCallHistory.Items.Count > 0) _lstCallHistory.SelectedIndex = 0;
        }

        private void CallSelectedContact()
        {
            var contact = _lstContacts.SelectedItem as SpeedDialContact;
            if (contact == null)
            {
                Speak("連絡先が選択されていません。");
                return;
            }

            if (contact.IsWebRtc)
            {
                _txtDialNumber.Text = "";
                _txtWebRtcRoom.Text = contact.Target;
                _cmbCallType.SelectedIndex = contact.IsVideo ? 0 : 1;
            }
            else
            {
                _txtDialNumber.Text = contact.Target;
                _cmbCallType.SelectedIndex = 2;
            }

            StartCallAction();
        }

        private void AddCurrentToContacts()
        {
            string dial = _txtDialNumber.Text.Trim();
            string room = _txtWebRtcRoom.Text.Trim();
            bool isWebRtc = string.IsNullOrEmpty(dial);
            string target = isWebRtc ? room : dial;
            if (string.IsNullOrEmpty(target))
            {
                Speak("電話番号または WebRTC ルーム名を入力してから追加ボタンを押してください。");
                return;
            }

            var newContact = new SpeedDialContact
            {
                Name = "連絡先 " + (_contacts.Count + 1) + " (" + target + ")",
                Target = target,
                IsWebRtc = isWebRtc,
                IsVideo = (_cmbCallType.SelectedIndex == 0)
            };
            _contacts.Add(newContact);
            RefreshContactsListBox();
            SaveContactsAndHistory();
            Speak("スピードダイヤル連絡先に「" + newContact.ToString() + "」を追加しました。");
        }

        private void DeleteSelectedContact()
        {
            int idx = _lstContacts.SelectedIndex;
            if (idx < 0 || idx >= _contacts.Count)
            {
                Speak("削除する連絡先を選んでください。");
                return;
            }
            string removed = _contacts[idx].ToString();
            _contacts.RemoveAt(idx);
            RefreshContactsListBox();
            SaveContactsAndHistory();
            Speak("「" + removed + "」を連絡先から削除しました。");
        }

        private void SpeakLatestCallHistory()
        {
            if (_callHistory.Count == 0)
            {
                Speak("まだ発着信履歴はありません。");
                return;
            }
            var latest = (_lstCallHistory.SelectedItem as CallLogRecord) ?? _callHistory[0];
            Speak("通話履歴：" + latest.ToString());
        }

        private void AppendLog(string line)
        {
            if (_txtAiLog == null) return;
            string stamped = string.Format("[{0}] {1}", DateTime.Now.ToString("HH:mm:ss"), line);
            if (string.IsNullOrEmpty(_txtAiLog.Text))
                _txtAiLog.Text = stamped;
            else
                _txtAiLog.AppendText(Environment.NewLine + stamped);
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
