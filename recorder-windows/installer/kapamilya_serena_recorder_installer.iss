; ============================================================================
; Kapamilya Serena Recorder for Windows v5.6.0 — Official Inno Setup 7.x+ Installer
; Built with Inno Setup 7.1.0+ (jrsoftware/issrc Latest Commit)
; 4-Language Selector Included: Japanese (ja), English (en), Tagalog (tl), Spanish (es)
; Special Tribute: Honoring Tomato-san (福島県のトマトさん) for contributing to Inno Setup
; Author & Exclusive Copyright Owner: SHINJI SAKIYAMA (shinjisakiyama@gmail.com)
; Pillars: Bayanihan • Magna Carta • Kapamilya (Under Bantay ng Dangal)
; ============================================================================

#define MyAppName "Kapamilya Serena Recorder for Windows"
#define MyAppShortName "Kapamilya Serena Recorder"
#define MyAppVersion "5.9.0"
#define MyAppPublisher "SHINJI SAKIYAMA (Bayanihan • Magna Carta • Kapamilya)"
#define MyAppURL "https://kapamilya.gitlab.io"
#define MyAppExeName "KapamilyaSerenaRecorder.exe"

[Setup]
AppId={{9F83B412-6D54-4E82-A1B9-8C72D65F8005}
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
OutputBaseFilename=Kapamilya-Serena-Recorder-Windows-Setup
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
ShowLanguageDialog=yes
UsePreviousLanguage=no
VersionInfoVersion=5.9.0.0
VersionInfoCompany={#MyAppPublisher}
VersionInfoDescription={#MyAppName} Official Inno Setup 7.x+ 4-Language Installer (JA/EN/TL/ES with Kapamilya BGM — Tribute to Tomato-san of Fukushima)
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
ja.ReadmeShortcut=README (ユーザーガイド)
en.ReadmeShortcut=README (User Guide)
tl.ReadmeShortcut=README (Gabay sa Gumagamit)
es.ReadmeShortcut=README (Guía del Usuario)

ja.OpenReadmePostInstall=README（ユーザーガイド・ショートカット一覧）を開く
en.OpenReadmePostInstall=Open README (User Guide & Shortcut Reference)
tl.OpenReadmePostInstall=Buksan ang README (Gabay sa Gumagamit at Listahan ng Shortcut)
es.OpenReadmePostInstall=Abrir README (Guía del Usuario y Atajos)

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
; Kapamilya Installer BGM extracted into temporary folder on launch
Source: "..\waves\kapamilya_installer_music.wav"; Flags: dontcopy

; Main Executable & Documentation
Source: "..\{#MyAppExeName}"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\documentation\README.txt"; DestDir: "{app}\documentation"; Flags: ignoreversion
Source: "..\waves\kapamilya_installer_music.wav"; DestDir: "{app}\waves"; Flags: ignoreversion

[Icons]
Name: "{group}\{#MyAppShortName}"; Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"
Name: "{group}\{cm:ReadmeShortcut}"; Filename: "{app}\documentation\README.txt"
Name: "{group}\{cm:UninstallProgram,{#MyAppShortName}}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppShortName}"; Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"; Tasks: desktopicon

[Registry]
Root: HKA; Subkey: "Software\KapamilyaSerenaRecorder"; ValueType: string; ValueName: "Language"; ValueData: "{language}"; Flags: uninsdeletekey

[Run]
Filename: "{app}\documentation\README.txt"; Description: "{cm:OpenReadmePostInstall}"; Flags: postinstall shellexec skipifsilent unchecked
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#MyAppName}}"; Flags: nowait postinstall skipifsilent

[Code]
function PlaySoundW(pszSound: string; hmod: THandle; fdwSound: DWORD): BOOL;
  external 'PlaySoundW@winmm.dll stdcall';

procedure InitializeWizard();
begin
  ExtractTemporaryFile('kapamilya_installer_music.wav');
  // SND_FILENAME ($00020000) or SND_ASYNC ($0001) or SND_LOOP ($0008) = $00020009
  PlaySoundW(ExpandConstant('{tmp}\kapamilya_installer_music.wav'), 0, $00020009);
end;

procedure DeinitializeSetup();
begin
  PlaySoundW('', 0, 0);
end;
