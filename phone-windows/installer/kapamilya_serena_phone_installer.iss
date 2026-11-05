; ============================================================================
; Kapamilya Serena Phone for Windows v5.8.0 — Official Inno Setup 7.x+ Installer
; Built with Inno Setup 7.1.0+ (jrsoftware/issrc Latest Commit 2527da7)
; 4-Language Selector Included: Japanese (ja), English (en), Tagalog (tl), Spanish (es)
; Special Tribute: Honoring Tomato-san (福島県のトマトさん) for contributing to Inno Setup
; Author & Exclusive Copyright Owner: SHINJI SAKIYAMA (shinjisakiyama@gmail.com)
; Pillars: Bayanihan • Magna Carta • Kapamilya (Under Bantay ng Dangal)
; ============================================================================

#define MyAppName "Kapamilya Serena Phone"
#define MyAppShortName "Kapamilya Serena Phone"
#define MyAppVersion "5.14.0"
#define MyAppPublisher "SHINJI SAKIYAMA (Bayanihan • Magna Carta • Kapamilya)"
#define MyAppURL "https://kapamilya.gitlab.io"
#define MyAppExeName "KapamilyaSerenaPhone.exe"

[Setup]
AppId={{8E94D725-9B46-4A82-C3D8-7F52B86A9104}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} v{#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}
AppUpdatesURL={#MyAppURL}
DefaultDirName={autopf}\{#MyAppShortName}
DefaultGroupName={#MyAppShortName}
AllowNoIcons=yes
LicenseFile=..\documentation\README.txt
OutputDir=..
OutputBaseFilename=Kapamilya-Serena-Phone-Windows-Setup
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
ShowLanguageDialog=yes
UsePreviousLanguage=no
VersionInfoVersion=5.14.0.0
VersionInfoCompany={#MyAppPublisher}
VersionInfoDescription={#MyAppName} Official Inno Setup 7.x+ 4-Language Accessible Installer (JA/EN/TL/ES — Tribute to Tomato-san of Fukushima)
VersionInfoCopyright=Copyright (c) 2026 SHINJI SAKIYAMA. All Rights Reserved.
VersionInfoProductName={#MyAppName}
VersionInfoProductVersion={#MyAppVersion}
CloseApplications=force

[Languages]
Name: "ja"; MessagesFile: "compiler:Languages\Japanese.isl"
Name: "en"; MessagesFile: "compiler:Default.isl"
Name: "tl"; MessagesFile: "Tagalog.isl"
Name: "es"; MessagesFile: "compiler:Languages\Spanish.isl"

[CustomMessages]
ja.ReadmeShortcut=README (ユーザーガイド・ショートカット一覧)
en.ReadmeShortcut=README (User Guide & Shortcuts)
tl.ReadmeShortcut=README (Gabay sa Gumagamit)
es.ReadmeShortcut=README (Guía del Usuario)

ja.OpenReadmePostInstall=README（ユーザーガイド・ショートカット一覧）を開く
en.OpenReadmePostInstall=Open README (User Guide & Shortcut Reference)
tl.OpenReadmePostInstall=Buksan ang README (Gabay sa Gumagamit at Listahan ng Shortcut)
es.OpenReadmePostInstall=Abrir README (Guía del Usuario y Atajos)

ja.MuteBgmButton=BGMを止める (&M)
en.MuteBgmButton=Stop BGM (&M)
tl.MuteBgmButton=Itigil ang BGM (&M)
es.MuteBgmButton=Detener BGM (&M)

ja.BgmStopped=BGMを停止しました
en.BgmStopped=BGM Stopped
tl.BgmStopped=Huminto na ang BGM
es.BgmStopped=BGM Detenido

[Dirs]
Name: "{app}"
Name: "{app}\documentation"
Name: "{app}\waves"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
; Kapamilya Installer BGM extracted into temporary folder on launch (Played ONCE, no loop!)
Source: "..\waves\kapamilya_installer_music.wav"; Flags: dontcopy

; Main Executable & Documentation
Source: "..\{#MyAppExeName}"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\documentation\README.txt"; DestDir: "{app}\documentation"; Flags: ignoreversion
Source: "..\waves\kapamilya_installer_music.wav"; DestDir: "{app}\waves"; Flags: ignoreversion

[Icons]
; Note: Never use '/' in [Icons] Name: so Windows never treats it as a missing subdirectory!
Name: "{group}\{#MyAppShortName}"; Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"
Name: "{group}\{cm:ReadmeShortcut}"; Filename: "{app}\documentation\README.txt"; WorkingDir: "{app}\documentation"
Name: "{group}\{cm:UninstallProgram,{#MyAppShortName}}"; Filename: "{uninstallexe}"; WorkingDir: "{app}"
Name: "{autodesktop}\{#MyAppShortName}"; Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"; Tasks: desktopicon

[Registry]
Root: HKA; Subkey: "Software\KapamilyaSerenaPhone"; ValueType: string; ValueName: "Language"; ValueData: "{language}"; Flags: uninsdeletekey

[Run]
Filename: "{app}\documentation\README.txt"; WorkingDir: "{app}\documentation"; Description: "{cm:OpenReadmePostInstall}"; Flags: postinstall shellexec skipifsilent unchecked
Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"; Description: "{cm:LaunchProgram,{#MyAppName}}"; Flags: nowait postinstall skipifsilent

[Code]
function PlaySoundW(pszSound: string; hmod: THandle; fdwSound: DWORD): BOOL;
  external 'PlaySoundW@winmm.dll stdcall';

var
  MuteBgmButton: TNewButton;
  BgmMuted: Boolean;

procedure OnMuteBgmClick(Sender: TObject);
begin
  PlaySoundW('', 0, 0);
  BgmMuted := True;
  if MuteBgmButton <> nil then
  begin
    MuteBgmButton.Caption := ExpandConstant('{cm:BgmStopped}');
    MuteBgmButton.Enabled := False;
  end;
end;

procedure InitializeWizard();
begin
  BgmMuted := False;
  ExtractTemporaryFile('kapamilya_installer_music.wav');
  // SND_FILENAME ($00020000) or SND_ASYNC ($0001) = $00020001 (Play ONCE only, NO loop!)
  PlaySoundW(ExpandConstant('{tmp}\kapamilya_installer_music.wav'), 0, $00020001);

  MuteBgmButton := TNewButton.Create(WizardForm);
  MuteBgmButton.Parent := WizardForm;
  MuteBgmButton.Left := WizardForm.ClientWidth - WizardForm.CancelButton.Left - WizardForm.CancelButton.Width;
  MuteBgmButton.Top := WizardForm.CancelButton.Top;
  MuteBgmButton.Width := WizardForm.CancelButton.Width + 40;
  MuteBgmButton.Height := WizardForm.CancelButton.Height;
  MuteBgmButton.Caption := ExpandConstant('{cm:MuteBgmButton}');
  MuteBgmButton.OnClick := @OnMuteBgmClick;
  MuteBgmButton.Anchors := [akLeft, akBottom];

  WizardForm.DirEdit.Hint := 'インストール先フォルダのパス入力欄 (Installation Directory Path)';
  WizardForm.GroupEdit.Hint := 'スタートメニューフォルダ名の入力欄 (Start Menu Folder Name)';
  WizardForm.TasksList.Hint := '追加タスクのチェックリスト (上下矢印で移動、スペースキーでチェック切替)';
  WizardForm.LicenseMemo.Hint := 'ユーザーガイドおよびライセンス本文 (矢印キーで閲覧、Tabキーで次へ移動)';
  WizardForm.ReadyMemo.Hint := 'インストール設定の最終確認一覧 (Enterキーでインストール開始)';
end;

procedure CurPageChanged(CurPageID: Integer);
var
  StepTitle: string;
begin
  StepTitle := '';
  case CurPageID of
    wpWelcome:
      if ActiveLanguage = 'ja' then StepTitle := '【画面 1/6: セットアップへようこそ — Enterで次へ】'
      else StepTitle := '[Step 1/6: Welcome — Press Enter for Next]';
    wpLicense:
      begin
        if ActiveLanguage = 'ja' then
        begin
          StepTitle := '【画面 2/6: ガイド確認 — 同意するを選んでEnterで次へ】';
          WizardForm.PageNameLabel.Caption := 'ガイドとライセンスの確認 (Tabキーで「同意する」を選びEnterで次へ)';
        end
        else StepTitle := '[Step 2/6: License Agreement]';
      end;
    wpSelectDir:
      begin
        if ActiveLanguage = 'ja' then
        begin
          StepTitle := '【画面 3/6: インストール先フォルダの指定 — Enterで次へ】';
          WizardForm.PageNameLabel.Caption := 'インストール先フォルダの指定 (そのままEnterで標準フォルダへ次へ)';
        end
        else StepTitle := '[Step 3/6: Select Destination Location]';
      end;
    wpSelectProgramGroup:
      begin
        if ActiveLanguage = 'ja' then
        begin
          StepTitle := '【画面 4/6: スタートメニューフォルダの指定 — Enterで次へ】';
          WizardForm.PageNameLabel.Caption := 'スタートメニューフォルダの指定 (そのままEnterで次へ)';
        end
        else StepTitle := '[Step 4/6: Select Start Menu Folder]';
      end;
    wpSelectTasks:
      begin
        if ActiveLanguage = 'ja' then
        begin
          StepTitle := '【画面 5/6: 追加タスクの選択 (スペースキーで切替) — Enterで次へ】';
          WizardForm.PageNameLabel.Caption := '追加タスクの選択 (上下矢印キーで項目を選び、スペースキーでチェック切替)';
        end
        else StepTitle := '[Step 5/6: Select Additional Tasks]';
      end;
    wpReady:
      begin
        if ActiveLanguage = 'ja' then
        begin
          StepTitle := '【画面 6/6: インストール準備完了 — Enterキーでインストール開始！】';
          WizardForm.PageNameLabel.Caption := 'インストール準備完了 (今すぐEnterキーを押すとインストールを開始します)';
        end
        else StepTitle := '[Step 6/6: Ready to Install — Press Enter to Install]';
      end;
    wpInstalling:
      if ActiveLanguage = 'ja' then StepTitle := '【インストール実行中... しばらくお待ちください】'
      else StepTitle := '[Installing... Please wait]';
    wpFinished:
      begin
        PlaySoundW('', 0, 0);
        if MuteBgmButton <> nil then MuteBgmButton.Visible := False;
        if ActiveLanguage = 'ja' then
        begin
          StepTitle := '【完了: セットアップが完了しました！ — Enterキーで起動】';
          WizardForm.PageNameLabel.Caption := 'Kapamilya Serena Phone セットアップ完了！ (Enterキーで完了して起動します)';
        end
        else StepTitle := '[Completed: Setup Finished — Press Enter to Launch]';
      end;
  end;

  if StepTitle <> '' then
    WizardForm.Caption := '{#MyAppName} v{#MyAppVersion} セットアップ — ' + StepTitle;
end;

procedure DeinitializeSetup();
begin
  PlaySoundW('', 0, 0);
end;
