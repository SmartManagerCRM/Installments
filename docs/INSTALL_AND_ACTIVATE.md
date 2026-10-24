# Installing and activating on a customer's phone

*(Written for the person who sets up the customer's phone. Works with no internet connection.)*

## 1. Install
1. Copy `SmartManagerInstallments.apk` to the phone (USB, WhatsApp, Drive...). Android 8.0 or newer.
2. Open the file. If Android asks, allow *Install unknown apps* for the app you used to open it.
3. Open **SmartManager Installments**. The first launch uses Arabic when the phone language is not Arabic/French/English;
   change it any time in *Settings -> Language*.

## 2. Activate (offline)
1. The *Activation* screen shows the **Device ID** and an **activation request** (`SMI-ACT-1:...`). Tap **Copy** or **Share** and
   send it to the license owner (WhatsApp, SMS, e-mail).
2. The owner creates the license in the License Generator (lifetime or duration) and sends back a `.smilic` file
   (or the one-line text starting with `SMI-LIC-1.`).
3. Tap **Import license file** (or paste the text and tap **Activate**). The screen confirms "Activated successfully".
4. *Settings -> License* shows customer, type, validity, days remaining and the Device ID.

If the license is for another phone the app says so and stores nothing.

## 3. First setup (2 minutes)
*Settings -> Business profile*: name, phone, address, logo, currency (TND by default), date format, receipt footer, country dialing
code (for WhatsApp, e.g. 216), contract terms (review them for your country's law!). Optional: *Product catalog*.
Do **not** use *Demo data* on a real phone; if it was used, *Settings -> Demo data -> Remove*.

## 4. Backups (important)
Nothing is uploaded and nothing is backed up automatically. *Settings -> Backup -> Create backup* produces one file; save it to Drive,
USB or the computer - weekly is a good habit. Optional password encryption: a forgotten password cannot be recovered.

## 5. New phone / lost phone
Install the app, **Restore** the latest backup, copy the new activation request to the owner, import the replacement license.
(The license is bound to one phone and is not included in backups.)

## 6. When the license expires
The app keeps working in **read-only mode**: view everything, search, create PDFs/CSV, make and restore backups. Contact the owner
to renew; import the new license under *Settings -> License -> Renew or replace*.
