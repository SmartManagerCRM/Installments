# Customer version - the simple way (no GitHub, no command line)

You only use the **License Manager** page (`license-manager/index.html`, double-click it). Nothing is uploaded anywhere.

## One time only (10 minutes)
1. **Tab 1 "Keys"** -> type a passphrase (10+ characters) -> **Create new production key**. A `.smikey` backup downloads.
   Keep **two copies offline** (USB stick + another place). This key signs every customer license.
2. Press **Copy public key (send it to your developer)** and paste it in the chat with Claude. It is safe to share: it can only *check* licenses, never create them.
3. Claude puts it in the app and builds the **unsigned customer APK**. You get a download link.
4. **Tab 5 "Customer app (APK)"** -> passphrase -> **Create my app key (once)**. A `.smiapp` backup downloads. Keep **two copies offline**.
   This key proves the app is yours; **without it you can never update customers' installed apps.**

## Every new app version
1. Download the new unsigned customer APK (link from Claude).
2. **Tab 5** -> *Choose APK file...* -> type your passphrase -> **Sign the APK**. The signed APK downloads.
3. Install it on your own phone once to check. Give customers **only the signed APK** (and its SHA-256 shown after signing).

## Every new customer
1. Customer installs the signed APK, opens it, and sends you the code from the activation screen.
2. **Tab 2 "Issue license"** -> paste the code -> customer name -> choose Lifetime or days -> **Issue license** -> send the `.smilic` file or the copied code.

## Never give customers
The debug APK, the unsigned APK, any `.smikey` / `.smiapp` file, the License Manager page.
