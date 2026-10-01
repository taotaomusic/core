import type { Dict } from "./zh";

/** English copy. Structure must match zh.ts exactly (enforced by Dict). */
export const en: Dict = {
  brand: "TaotaoMusic",
  docTitle: "TaotaoMusic · Cross-platform private music player",
  nav: {
    features: "Features",
    download: "Download",
    selfhost: "Self-host",
    github: "GitHub",
    themeTitle: "Toggle theme",
    langTitle: "中文",
  },
  hero: {
    badge: "Self-hosted music · Synced everywhere",
    titleBefore: "Your entire library, ",
    titleAccent: "always with you",
    sub: "TaotaoMusic is a fully self-hostable, cross-platform music service: one account across Android, Windows and Web, with playlists, favorites and playback progress synced in the cloud — plus word-by-word lyrics, multiple audio qualities and one-tap share links.",
    android: "Download for Android",
    desktop: "Download for Windows",
    more: "Explore features",
    metaPrefix: "Open source at ",
    repo: "taotaomusic/core",
    metaSuffix: " · Own your data · Free to use",
    playerTitle: "Peach Serenade",
    playerArtist: "Sample track · Lossless",
    playerLyric: "Singing your favorite songs, over and over",
    chipLossless: "Lossless",
    chipLyrics: "Synced lyrics",
    chipSync: "Cloud sync",
  },
  features: {
    kicker: "Features",
    title: "Great listening, by design",
    desc: "An everyday experience polished across clients and server — not a pile of features.",
    items: [
      {
        icon: "cloud",
        title: "Cloud playlist sync",
        desc: "Playlists, favorites, history and playback progress live in the cloud — sign in on a new device and pick up where you left off.",
      },
      {
        icon: "mic",
        title: "Word-by-word lyrics",
        desc: "Karaoke-style highlighting refreshes character by character, so you never miss a beat when singing along.",
      },
      {
        icon: "equalizer",
        title: "Multiple audio qualities",
        desc: "Switch freely from standard to lossless to match your network and data plan.",
      },
      {
        icon: "search",
        title: "Aggregated search",
        desc: "One search across multiple sources — preview, favorite and download in a single result.",
      },
      {
        icon: "link",
        title: "Share to listen",
        desc: "Turn any song into a short link — friends listen right in the browser, no app required.",
      },
      {
        icon: "shield",
        title: "Encrypted transport",
        desc: "A custom transport-encryption layer protects sensitive APIs; keys are delivered dynamically by the server.",
      },
    ],
  },
  download: {
    kicker: "Downloads",
    title: "Three platforms, one home",
    desc: "Every package is built and signed by a unified CI, with traceable, gap-free version numbers.",
    android: {
      title: "Android",
      desc: "Continuously built, signed APKs with feature parity to desktop; playlists, favorites and progress stay in sync.",
      meta: "APK · Rolling GitHub Release",
      action: "Get the APK",
    },
    windows: {
      title: "Windows desktop",
      desc: "A native window experience with in-app auto-updates enabled — install once and stay current.",
      meta: "MSI / installer · Signature-verified auto-update",
      action: "Get the installer",
    },
    web: {
      title: "Web share player",
      desc: "Tap “Share” in the app to create a short link; anyone can listen in a modern browser, no login needed.",
      link: "https://your-domain/s/xxxxxxxx",
      note: "Opens from the in-app share · Nothing to install",
    },
  },
  selfhost: {
    kicker: "Self-hosting",
    title: "Built for self-hosting",
    desc: "The server is a standard NestJS + PostgreSQL app — one command to run. Before every release, 300+ API contract checks run against an isolated verification database; a release ships only when everything is green.",
    hint: "Images follow the repository: pick latest, a semantic version, or a short commit hash.",
    cards: [
      {
        icon: "box",
        title: "Docker deployment",
        desc: "One command pulls a runtime image that reuses contract-verified build artifacts.",
      },
      {
        icon: "badge-check",
        title: "Contract checks",
        desc: "API behavior is locked by 300+ automated contract checks — upgrades never silently break clients.",
      },
      {
        icon: "dashboard",
        title: "Admin console",
        desc: "Role tiers, two-factor auth, forced password rotation and audit logs built in.",
        link: "Open the admin console →",
      },
      {
        icon: "code",
        title: "Open API",
        desc: "Sharing, playlists and more are exposed as REST for your own automation.",
        link: "",
      },
    ],
  },
  footer: {
    slogan: "TaotaoMusic · Your favorite songs, always within reach",
    admin: "Admin console",
    status: "Server status",
    copy: "© 2026 TaotaoMusic",
  },
};
