Android Adaptive Icon Pack
================================
What's inside:
- mipmap-mdpi ... mipmap-xxxhdpi: ic_launcher_foreground.png, ic_launcher_background.png, ic_launcher.png (legacy)
- mipmap-anydpi-v26/ic_launcher.xml and ic_launcher_round.xml

Usage:
1) Copy folders into app/src/main/res/
2) Ensure your manifest uses android:icon="@mipmap/ic_launcher" and android:roundIcon="@mipmap/ic_launcher_round"
3) Clean & Rebuild project.

Notes:
- Background is a warm gradient (#F9A66C -> #FFD6E7)
- Foreground uses ~72% safe area per Android adaptive icon guidance.
