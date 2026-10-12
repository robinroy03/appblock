# Security

If you find a security issue, please **don't open a public issue** — email
**support@robinroy.xyz** instead.

Worth knowing when assessing impact: the app holds **Usage access** (it can see which
app is in the foreground and how long apps are used) and **Display over other apps**
(it draws the block wall over them), but it has **no network permission** — nothing
ever leaves the device. All data (your rules and usage log) lives in local app
storage, which Android's own Auto Backup may copy to your Google account like any
app's.
