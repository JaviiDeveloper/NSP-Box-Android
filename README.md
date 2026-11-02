# NSP Box Android: A Switch 2 Game Packager

[Download](https://github.com/JaviiDeveloper/NSP-Box-Android/releases/latest) · [Changelog](CHANGELOG.md) · [Disclaimer](DISCLAIMER.md)

An Android app that packages a complete, already-extracted ExeFS/RomFS snapshot into a plaintext NSP for compatible emulators. Choose a folder, add your own artwork, and build an NSP.

Use **Game** for complete ExeFS/RomFS snapshots and **DLC** for separate RomFS-only add-ons.

**Piracy is not supported.** Use only files and artwork you have lawfully obtained and are authorized to use. No games, game artwork, firmware or Nintendo keys are provided. Nintendo Switch, Nintendo Switch 2 and related marks belong to Nintendo; game content belongs to its respective rights holders. This is an independent project, not affiliated with, sponsored by or endorsed by Nintendo.

> **Caution — experimental software:** The app does not change emulator memory settings. Verification checks package integrity, not booting or gameplay.

A startup caution explains this before using the app. **Don't show this again** stores only a local preference on the device; the same guidance remains in the **About** tab.

NSP output streams directly to its destination without loading the entire package into memory. The app includes square artwork cropping, progress/cancellation, and full output verification.

**Experimental build with limited real-world testing.** If anything goes wrong, [open an issue](https://github.com/JaviiDeveloper/NSP-Box-Android/issues/new) with your app/emulator versions, reproduction steps and error messages. Do not upload game files, keys or personal paths.

## Quick start

1. Install the APK from the [releases page](https://github.com/JaviiDeveloper/NSP-Box-Android/releases/latest).
2. Open NSP Box. Grant the **All Files Access** permission when prompted — required to read game snapshot folders and write NSP output.
3. Tap **Browse folder** in the Game tab and select your main game dump folder.
4. Select the complete snapshot you want from the list. The app reads its title ID from `main.npdm`.
5. Edit the displayed game name/version if needed. Add artwork and tap **Crop icon** to position it. Select an NSP destination in Output.
6. Leave **Verify after building** enabled. **Create package folder** is on by default: a folder named after the output file contains the package, a metadata companion and a JSON verification report. Then tap **Build NSP**.

The app never overwrites an existing package and does not modify your source files. Cancellation removes the unfinished package. Verification works in both modes. Allow roughly the input size plus 2% and 32 MB free storage space. Packages up to **32 GiB** are supported.

With **Create package folder** enabled, `Packed/Game.nsp` is saved as `Packed/Game/Game.nsp`, with `main.npdm` and `Game.nsp.report.json` beside it. The companion is an optional workaround for certain emulator loaders' early Switch 2 memory/clock detection. Keep each game/snapshot in a separate folder if using it.

NSP is a PFS0 container with Program, Control and Meta NCAs for games, or Data and Meta NCAs for DLC. It is not compressed. Outputs are unsigned plaintext emulator repacks; this does not make them authenticated retail-console packages. Loading depends on the emulator's plaintext-content support.

## Accepted folder layouts

Direct snapshot:

```text
My Game/
  exefs/
    main
    main.npdm
    rtld, sdk, subsdk* ... (as present in your dump)
  romfs/
    ... complete game filesystem ...
```

Main folder with snapshots:

```text
My Game/
  base/
    exefs/...
    romfs/...
  updates/
    65536/
      exefs/...
      romfs/...
```

An empty `base` folder is fine when an update folder already contains the complete mounted game filesystem. A folder containing only update differences is **not** sufficient. Completeness cannot be reliably determined from filenames; select a snapshot you know is complete.

The Game tab does not merge separate updates or DLC; DLC folders are left out of game snapshots. Use the DLC tab to create a separate add-on package for installation through a compatible emulator. Multi-program applications, patch-only/BKTR data, encrypted NSP/XCI inputs, compressed ROM packages, decryption, and authenticated cartridge reconstruction are outside this release's scope. This is a snapshot packager, not an emulator or a universal game converter.

## Artwork and metadata

Artwork is exported as a **256×256 JPEG**, suitable for the emulator library icon. New artwork starts with a centered square crop. **Crop icon** opens an editor with drag-to-position, pinch-to-zoom, a live icon preview, and Apply/Cancel. **Fit whole image** retains the entire image with padding. Cropping never changes the original file.

Supported inputs include PNG, JPEG, WEBP, BMP, and other common formats decoded by Android's BitmapFactory.

The app embeds the selected crop in the game NSP. No game artwork ships with this app.

If a standard `control.nacp` is present at the snapshot root or under `control/`, the app preserves it and updates uncompressed title text and the display version. Otherwise it generates basic control metadata. Generated fields are not a recreation of all original save-size, network, language, or DLC settings.

## Emulator key setup

Some emulator builds require key entries even when NCA headers and sections are plaintext. This app includes locally generated placeholders, **not Nintendo keys**. They cannot decrypt retail content. The app does not install them or change emulator configuration automatically.

If the emulator reports a missing key for this app's plaintext images, open the **Keys** tab and use **Set up emulator keys folder**. Select the emulator's `keys` folder from its user data directory. Setup adds only missing plaintext placeholders, preserves existing entries, and never writes `prod.keys`. Restart the emulator afterwards.

The **Show Keys** button exports the placeholder key text for manual setup.

## Storage permission

NSP Box requires the **Manage All Files** permission (All Files Access) on Android 11 and above. This is needed to:
- Read game snapshot folders anywhere on shared storage
- Write NSP output files to your chosen destination

The permission request appears at first launch. If denied, go to **Settings → Apps → NSP Box → Permissions → Files and media** and grant **All files access**. The app uses this permission only for the files you explicitly select; it does not scan or upload any content.

## Portability and privacy

- Android 11 (API 30) and above; no root required.
- No telemetry, upload or automatic downloading. Key setup runs only when explicitly selected; builds do not change emulator configuration.
- When package-folder output is enabled, paths appear in the local JSON report. Review or remove reports before sharing them if you do not want to disclose local folder names.

## Source and redistribution

Source code is available on the [repository page](https://github.com/JaviiDeveloper/NSP-Box-Android). The application code is released under the MIT license; third-party components retain their own licenses.

You may redistribute the app and source with the required license notices. The software was developed with AI assistance. This package includes no Nintendo code, game data, game cover artwork, console firmware, tickets, certificates, or Nintendo cryptographic keys. Use your own extracted data and artwork; redistribution rights for input/output content are separate from the app's license.

This Android port is based on [NSP Box](https://github.com/smarkyie/NSP-Box) by smarkyie.
