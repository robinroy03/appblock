> [!IMPORTANT]
> This was written entirely with AI, and I haven't verified any of the code. Please use it with caution — though I do trust [Claude's Plan](https://www.youtube.com/watch?v=gFx-NjTw3sM). The app will go on the Play Store after I do a manual security and performance audit. All apps from me will be held to the highest quality standards. I will not ship slop.

# appblock

| ![AppBlock home screen](public/home.jpg) | ![Blocked screen](public/blocked.jpg) |
| ---------------------------------------- | ------------------------------------- |

Pick apps and give each a budget: **allow X minutes per rolling Y minutes**. When the budget
is spent, opening the app bounces you to a "blocked, try again in N min" screen.

No dependencies, no network, no analytics. Everything stays on your phone.

## Tie yourself to the mast

![Ulysses and the Sirens](public/John_William_Waterhouse_-_Ulysses_and_the_Sirens_(1891).jpg)

*Ulysses and the Sirens, John William Waterhouse, 1891. That's him on the mast.*

Odysseus had to sail past the Sirens, whose song lured every sailor to
shipwreck. Every captain before him knew the danger. Knowing was never enough.

Odysseus wasn't stronger than the others. He was wiser: he knew that in the
moment, with the song in his ears, he'd be as weak as anyone. So he had his
crew tie him to the mast before the singing started.

Your phone sings too. And you already know knowing isn't enough. You've
promised yourself "just five minutes" before.

AppBlock is your mast. You decide the rules now, while you're clearheaded: a
few minutes per app, every few hours. Later, when the pull comes, the decision
is already made. Not zero access, no daily quota to blow through by 5am. Just
enough to check in, never enough to drown.

## What exactly gets blocked

**Blocked during a block window:**

- **Opening the app** — from the launcher, recents, widgets, share sheet, links,
  anything. The moment the app's window hits the foreground the block wall covers
  it, with a button to go home.
- **Tapping one of its notifications** — the notification arrives and is readable,
  but tapping it opens the app → blocked like any other open.
- **Overstaying** — if you're mid-scroll when the budget runs out (checked every
  5 seconds), the wall drops over the app.

**NOT blocked:**

- **Notifications** — they arrive, buzz, and show on the lock screen exactly as
  always. Inline actions (e.g. replying to a message from the notification) work,
  since they never open the app's window.
- **Background activity** — messages keep syncing, downloads keep running, audio
  keeps playing. A blocked Spotify keeps playing what's queued; you just can't
  open its UI.
- The budget clock only ticks while the app is **on screen** — background use
  costs nothing.

**Edge cases:**

- **Incoming calls in blocked apps** (WhatsApp/Telegram): the ring notification
  appears, but the full-screen incoming-call UI is the app's window — answering
  while the app is over budget will likely get bounced. Think twice before
  blocking an app you take calls on.
- **Picture-in-picture** mini-players are untested; entering the app to start one
  is blocked regardless.
- A blocked app's content shown _inside another app_ (e.g. a YouTube video
  embedded in a browser) is not blocked — only the app's own windows are.
