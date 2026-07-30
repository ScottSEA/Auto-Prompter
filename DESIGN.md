---
name: Auto-Prompter
description: A quiet, distance-readable interface that keeps presenters focused on delivery.
colors:
  shell-bg: "oklch(1.000 0.000 0)"
  shell-surface: "oklch(0.965 0.006 36)"
  shell-surface-strong: "oklch(0.925 0.012 36)"
  ink: "oklch(0.205 0.018 36)"
  ink-muted: "oklch(0.470 0.025 36)"
  primary: "oklch(0.495 0.134 36)"
  primary-deep: "oklch(0.420 0.140 36)"
  accent: "oklch(0.340 0.100 210)"
  prompt-bg: "oklch(0.080 0.000 0)"
  prompt-ink: "oklch(0.965 0.000 0)"
  prompt-passed: "oklch(0.620 0.012 36)"
  error: "oklch(0.500 0.180 25)"
typography:
  headline:
    fontFamily: "system-ui, -apple-system, BlinkMacSystemFont, Segoe UI, sans-serif"
    fontSize: "24px"
    fontWeight: 650
    lineHeight: 1.2
    letterSpacing: "-0.01em"
  title:
    fontFamily: "system-ui, -apple-system, BlinkMacSystemFont, Segoe UI, sans-serif"
    fontSize: "18px"
    fontWeight: 600
    lineHeight: 1.3
    letterSpacing: "normal"
  body:
    fontFamily: "system-ui, -apple-system, BlinkMacSystemFont, Segoe UI, sans-serif"
    fontSize: "16px"
    fontWeight: 400
    lineHeight: 1.5
    letterSpacing: "normal"
  prompt:
    fontFamily: "system-ui, -apple-system, BlinkMacSystemFont, Segoe UI, sans-serif"
    fontSize: "48px"
    fontWeight: 500
    lineHeight: 1.35
    letterSpacing: "0.005em"
  label:
    fontFamily: "system-ui, -apple-system, BlinkMacSystemFont, Segoe UI, sans-serif"
    fontSize: "14px"
    fontWeight: 600
    lineHeight: 1.3
    letterSpacing: "0.01em"
rounded:
  sm: "8px"
  md: "12px"
  lg: "20px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "12px"
  lg: "16px"
  xl: "24px"
  xxl: "32px"
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.shell-bg}"
    rounded: "{rounded.md}"
    padding: "12px 20px"
    height: "48px"
  button-secondary:
    backgroundColor: "{colors.shell-surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.md}"
    padding: "12px 20px"
    height: "48px"
  prompt-surface:
    backgroundColor: "{colors.prompt-bg}"
    textColor: "{colors.prompt-ink}"
    typography: "{typography.prompt}"
    rounded: "{rounded.lg}"
    padding: "32px"
  input:
    backgroundColor: "{colors.shell-bg}"
    textColor: "{colors.ink}"
    rounded: "{rounded.md}"
    padding: "12px 16px"
    height: "48px"
---

# Design System: Auto-Prompter

## 1. Overview

**Creative North Star: "The Quiet Stage"**

Auto-Prompter should feel like the moment immediately before a confident performance: composed,
quiet, and ready. The working shell is restrained and familiar; the prompt itself becomes a
high-contrast near-black stage that holds attention at distance. Burnt sienna marks primary intent
without flooding the interface, while deep teal is reserved for stable informational state.

The system explicitly rejects the PRODUCT.md anti-reference of a **cluttered developer diagnostics
screen**. Diagnostics, implementation terminology, and secondary controls must never compete with
the script. Motion communicates scrolling or state changes only, completes in 150-250 ms, and
becomes instant when reduced motion is requested.

**Key Characteristics:**
- Script-first hierarchy with controls visually subordinate to reading.
- Strong contrast and generous targets for distance and hands-off operation.
- Flat tonal layering instead of decorative shadows or nested cards.
- Familiar touch, keyboard, and presentation-remote affordances.
- Restrained brand color used only for intent, progress, and focus.

## 2. Colors

The palette pairs a pure, clinical working shell with a true dark prompt surface; warmth lives in
the action color, never in a cream-tinted background.

### Primary
- **Burnished Cue:** The only primary-action fill and active progress marker. White text is mandatory
  on this saturated color.
- **Deep Cue:** Hover, pressed, and high-emphasis focus state for Burnished Cue.

### Secondary
- **Studio Teal:** Stable informational state, selected indicators, and links. It is never a second
  competing call-to-action color.

### Neutral
- **Pure Shell:** Default app background and input fill.
- **Quiet Surface:** Toolbars, grouped controls, and secondary regions.
- **Stage Black:** Prompt viewport background; it is a true neutral, not blue-black or purple-black.
- **Stage White:** Prompt copy and essential controls over Stage Black.
- **Passed Copy:** Already-spoken prompt text; subdued but still readable.
- **Warm Ink / Muted Ink:** Primary and secondary shell text.

### Named Rules

**The Ten Percent Rule.** Burnished Cue occupies no more than ten percent of a working screen. Its
rarity signals action.

**The Two Worlds Rule.** Shell surfaces stay light; the reading viewport stays dark. Do not blend
them into a field of gray cards.

**The No Color-Only Rule.** Every error, mode, recording, and follow state requires text or an icon
in addition to color.

## 3. Typography

**Display Font:** Platform system sans with native fallbacks

**Body Font:** Platform system sans with native fallbacks

**Label/Mono Font:** No separate family

**Character:** One familiar sans family keeps the product out of the way. Hierarchy comes from
size, weight, and space rather than ornamental type pairing.

### Hierarchy
- **Headline** (650, 24px, 1.2): Screen and major workspace titles only.
- **Title** (600, 18px, 1.3): Section titles and current mode.
- **Body** (400, 16px, 1.5): Settings, explanations, and editor copy; prose is capped near 70ch.
- **Prompt** (500, 48px default, 1.35): Distance-readable script text, user-scalable without
  changing the control scale.
- **Label** (600, 14px, 1.3): Buttons, compact metadata, and status labels; sentence case only.

### Named Rules

**The Distance Rule.** Prompt copy starts at 48px-equivalent sizing and remains independently
scalable. Never shrink script text to fit more controls.

**The Sentence-Case Rule.** Buttons and labels use sentence case. All-caps interface chrome is
prohibited.

## 4. Elevation

The system is flat by default. Depth comes from tonal contrast, spacing, and full one-pixel
boundaries where separation is necessary. Shadows are reserved for transient surfaces that must
float above content, such as menus or future dialogs; ordinary cards and toolbars do not cast
shadows.

### Named Rules

**The Flat Stage Rule.** The prompt viewport earns focus through contrast and scale, not a glow or
heavy shadow.

## 5. Components

### Buttons
- **Shape:** Gently curved rectangle (12px radius) with a minimum 48px height and generous horizontal
  padding.
- **Primary:** Burnished Cue fill with Pure Shell text; one primary action per local task.
- **Hover / Focus:** Deep Cue on hover/press; a visible two-pixel focus treatment that does not rely
  on color alone.
- **Secondary:** Quiet Surface fill or a full one-pixel boundary with Warm Ink text.

### Cards / Containers
- **Corner Style:** 12px for controls and settings; 20px for the signature prompt surface.
- **Background:** Quiet Surface for grouped tools; Pure Shell for editable content; Stage Black for
  the prompt.
- **Shadow Strategy:** None at rest.
- **Border:** Full one-pixel boundary when adjacent tonal layers are insufficient.
- **Internal Padding:** 16px for controls, 24-32px for the prompt.

### Inputs / Fields
- **Style:** Pure Shell fill, full boundary, 12px radius, 48px minimum height.
- **Focus:** Strong boundary plus visible focus treatment.
- **Error / Disabled:** Text explanation accompanies the state; disabled fields remain legible.

### Navigation
- Use familiar tabs or a compact top bar when the product grows beyond one workspace. Active state
  uses weight, text, and a restrained cue marker; navigation never overlays the prompt during use.

### Prompt Surface
- Stage Black background, Stage White current/ahead copy, and Passed Copy for completed text.
- The reading horizon stays around 40% from the top. Follow scrolling moves only outside a dead band.
- Speech-follow corrections snap to the new horizon without animation; recognition latency must not
  be compounded by decorative motion.
- Touch or hardware navigation enters Manual Hold immediately; resuming never jumps backward.
- Fullscreen expands Stage Black edge-to-edge and removes every workspace element except the prompt
  and one outlined, 48dp `Exit fullscreen` control in the lower-right safe area.
- Speech following and hardware controls remain active when the workspace chrome is hidden.

## 6. Do's and Don'ts

### Do:
- **Do** make the script the largest, highest-contrast object in the workspace.
- **Do** keep touch targets at least 48px and provide keyboard/presentation-remote equivalents.
- **Do** use tonal grouping and spacing before introducing borders.
- **Do** make motion state-driven, bounded to 150-250 ms, and removable through reduced motion.
- **Do** show capability, permission, storage, and download failures in plain product language.

### Don't:
- **Don't** recreate the PRODUCT.md anti-reference: a **cluttered developer diagnostics screen**.
- **Don't** expose module names, event classes, generations, or recognizer internals in production UI.
- **Don't** use nested cards, equal-weight panels, heavy shadows, glassmorphism, or gradient text.
- **Don't** resemble a dense broadcast control room or a flashy social-media creator app.
- **Don't** move the prompt while Manual Hold is active or hide essential controls behind precise
  pointer gestures.
