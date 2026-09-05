# Custom Skin Provider

Emote converts individual player-skin regions into textures for display entities.
MineSkin is used by default, but registering a Minecraft account automatically enables the custom provider, which uploads textures through that account instead.

!!! warning "Use a dedicated account"
    The registered account's active Minecraft skin changes every time a texture is generated.
    Emote does not restore the original skin afterward, so use a dedicated Minecraft: Java Edition account rather than an account used for normal play.

## Provider selection

Emote selects the provider automatically based on the registered credentials.

| State                                      | Selected provider       |
|--------------------------------------------|-------------------------|
| One or more Minecraft accounts registered | Custom account provider |
| No accounts registered                     | MineSkin                |

Both providers check the same cache before generating a texture.
An image with the same content and model type is not uploaded again when its texture is already cached.

Emote does not automatically fall back to MineSkin when a registered account requires a new login or the credential store cannot be opened.
Use `/emote account` to inspect the account status, then sign in again or resolve the credential-store problem.

---

## Requirements

- A Minecraft: Java Edition account allowed to change its skin
- A user who can run commands with server owner permission
- Server network access to Microsoft, Xbox Live, and Minecraft Services

### Linux preparation

On Windows, Emote uses DPAPI with the current Windows user credentials when no other key is configured.
On Linux and other operating systems, set the `EMOTE_ACCOUNT_KEY` environment variable before starting the server.

Generate a Base64-encoded 32-byte key with:

```bash
openssl rand -base64 32
```

Store the generated key securely and provide the same value through `EMOTE_ACCOUNT_KEY` every time the server starts.
When using systemd or Docker, make sure the value is passed to the server process there as well.

!!! danger "Protect the encryption key"
    If `EMOTE_ACCOUNT_KEY` is lost or changed, the existing `accounts.bin` cannot be decrypted.
    Do not place the key in `config.json`, a Git repository, or server logs.

---

## Registering an account

1. If your environment requires `EMOTE_ACCOUNT_KEY`, start the server with that variable configured.
2. Run `/emote account login` with server owner permission.
3. Open the **Microsoft login** link shown in chat.
4. Enter the accompanying one-time code and sign in with the Microsoft account you want to register.
5. Confirm that `Connected bake account: <account name>` appears in chat.
6. Run `/emote account` to verify the selected provider and account status.

Login uses the Microsoft device-code flow, so the Minecraft server and web browser do not need to run on the same computer.

### Multiple accounts

Run `/emote account login` again to add another account.
When several accounts are registered, new upload tasks are distributed between them in round-robin order.
Each account processes one upload at a time, allowing different textures to be handled in parallel when more accounts are added.

Signing in with the same Minecraft account again updates its credentials instead of creating a duplicate entry.
Remove a registered account with `/emote account remove <name or UUID>`.

---

## Credential storage

Registered account refresh tokens are stored in encrypted form at:

```text
config/emote/accounts.bin
```

- Windows uses DPAPI when `EMOTE_ACCOUNT_KEY` is not set.
- When `EMOTE_ACCOUNT_KEY` is set, AES-256-GCM is used on every operating system.
- Access tokens are refreshed when needed and are not stored permanently.

Do not edit `accounts.bin` directly.
When creating backups, preserve both the file and its encryption key, preferably in separate secure locations.

A file protected with Windows DPAPI cannot be opened by another Windows user or on a Linux server.
Before migrating the server, either configure `EMOTE_ACCOUNT_KEY` on Windows and register the accounts again, or sign in again on the new server.

---

## Troubleshooting

### `Set EMOTE_ACCOUNT_KEY to a Base64-encoded 32-byte key`

The encryption key is not configured on the Linux server.
Set the key and restart the Minecraft server process completely.

### `Cannot read encrypted account store`

The current environment cannot decrypt `accounts.bin`.

- Confirm that `EMOTE_ACCOUNT_KEY` matches the value used when the accounts were registered.
- For a Windows DPAPI file, confirm that the server runs under the same Windows user.
- After a server migration, restore the original environment and key or register the accounts again in the new environment.

While the credential store is unavailable, Emote does not overwrite it with an empty store or silently switch to MineSkin.

### `Account login expired`

The device-code authorization was not completed before the time limit.
Run `/emote account login` again to receive a new code.

### `login required`

The stored refresh token expired or was revoked, or the Minecraft API rejected the refreshed session.
Run `/emote account login` and sign in with the same account again.

### Failure during `Microsoft`, `Xbox Live`, `Xbox XSTS`, or `Minecraft login`

The error message identifies the authentication stage and HTTP status.
If it also contains an `XErr` or `AADSTS` code, record that code and check the account's Xbox profile, Minecraft ownership, organization sign-in policies, and the server network.
Error messages do not include access tokens or detailed descriptions returned by the provider.
