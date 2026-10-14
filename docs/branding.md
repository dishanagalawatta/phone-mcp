# Phone MCP — Brand & Icon Guidelines

This document details the brand visual identity, master asset files, color palette, and Android icon architecture for **Phone MCP**.

---

## 1. Brand Identity

The Phone MCP logo combines a modern smartphone hardware silhouette with the Model Context Protocol (MCP) hub-and-spoke network topology, symbolizing on-device phone hardware exposed as a connected agent server.

```
       [Top-Right: Cyan #3AD7CC]
             \
              \  (Linear Gradient -37°)
               \
       [Bottom-Left: Violet #8D4FF3]
```

### Color Palette

| Name | Hex | RGB | Role |
| :--- | :--- | :--- | :--- |
| **Teal / Cyan** | `#3AD7CC` | `rgb(58, 215, 204)` | Gradient Stop 0% (Top-Right) |
| **Sky Cyan** | `#22D3EE` | `rgb(34, 211, 238)` | Gradient Stop 20% |
| **Vibrant Blue** | `#3B82F6` | `rgb(59, 130, 246)` | Gradient Stop 45% (Center) |
| **Indigo** | `#6366F1` | `rgb(99, 102, 241)` | Gradient Stop 70% |
| **Royal Violet** | `#8D4FF3` | `rgb(141, 79, 243)` | Gradient Stop 100% (Bottom-Left) |
| **White** | `#FFFFFF` | `rgb(255, 255, 255)` | App Icon Background / Notification Glyph |

---

## 2. Master Repository Assets (`assets/logo/`)

All canonical brand assets live in `assets/logo/`:

| File | Resolution / Canvas | Description |
| :--- | :--- | :--- |
| [`logo.svg`](../assets/logo/logo.svg) | `512×512` (viewBox) | Master brand logo mark with transparent background, centered with balanced padding. |
| [`logo-tight.svg`](../assets/logo/logo-tight.svg) | `372×420` (viewBox) | Tight-cropped logo mark without outer padding for inline documentation badges. |
| [`logo.png`](../assets/logo/logo.png) | `1024×1024` PNG | High-resolution raster export with transparent background. |
| [`app-icon.svg`](../assets/logo/app-icon.svg) | `512×512` (viewBox) | Master Android app icon with solid white background and Google Play standard rounded corners (`rx="102.4"`). |
| [`app-icon.png`](../assets/logo/app-icon.png) | `512×512` PNG | High-resolution Google Play Store icon. |
| [`app-icon-round.svg`](../assets/logo/app-icon-round.svg) | `512×512` (viewBox) | Circular variant with solid white background. |
| [`app-icon-round.png`](../assets/logo/app-icon-round.png) | `512×512` PNG | High-resolution circular icon render. |

---

## 3. Android App Icon Architecture (`app/src/main/res/`)

The Android application implements full adaptive icon specifications (API 26+) with Material You dynamic theming (API 33+) and legacy raster fallbacks.

### A. Adaptive Icons (`mipmap-anydpi-v26/`)
- **Main Icon**: [`mipmap-anydpi-v26/ic_launcher.xml`](../app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml)
- **Round Icon**: [`mipmap-anydpi-v26/ic_launcher_round.xml`](../app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml)

Both definitions combine three distinct layers:
1. **Background** (`@drawable/ic_launcher_background`): $108\text{dp} \times 108\text{dp}$ solid `#FFFFFF` white vector.
2. **Foreground** (`@drawable/ic_launcher_foreground`): $108\text{dp} \times 108\text{dp}$ vector with the logo mark scaled into the central $66\text{dp}$ safe-zone circle to prevent launcher mask clipping.
3. **Monochrome** (`@drawable/ic_launcher_monochrome`): $108\text{dp} \times 108\text{dp}$ flat silhouette vector enabled for Android 13+ Material You themed icons (allowing the system to tint the glyph according to the user's wallpaper palette).

### B. Fallback Raster Mipmaps
For legacy Android launchers and systems, pre-rendered icons with white background and rounded corners are located across all DPI bins:
- `mipmap-mdpi/`: $48\text{px}$
- `mipmap-hdpi/`: $72\text{px}$
- `mipmap-xhdpi/`: $96\text{px}$
- `mipmap-xxhdpi/`: $144\text{px}$
- `mipmap-xxxhdpi/`: $192\text{px}$

### C. System Notification Icon
- **Path**: [`app/src/main/res/drawable/ic_notification.xml`](../app/src/main/res/drawable/ic_notification.xml)
- **Format**: $24\text{dp} \times 24\text{dp}$ pure white silhouette vector drawable on transparent background, strictly adhering to Android status bar notification icon requirements. Used by `McpServerService`.

### D. In-App Brand Vector
- **Path**: [`app/src/main/res/drawable/ic_logo.xml`](../app/src/main/res/drawable/ic_logo.xml)
- **Format**: $512\text{dp} \times 512\text{dp}$ full-fidelity vector drawable with gradient fill for Compose screens and cards.
