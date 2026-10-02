#!/usr/bin/env bash
# Creates the permanent Android signing key, once, on your own machine.
#
# Android installs an update over the existing app only when both are signed
# with the same key, so keep the keystore this writes somewhere safe. If it is
# lost, the next build can't update the installed app: you'd have to uninstall
# (losing your lookup history) and install fresh.
#
# Prints the four values to add as GitHub repository secrets
# (Settings → Secrets and variables → Actions → New repository secret).

set -euo pipefail

out="${1:-read-depth-release.jks}"
alias="read-depth"

if [[ -e "$out" ]]; then
	echo "$out already exists; refusing to overwrite a signing key." >&2
	exit 1
fi

read -r -s -p "Choose a keystore password (6+ characters): " password
echo

keytool -genkeypair \
	-keystore "$out" \
	-storetype PKCS12 \
	-alias "$alias" \
	-keyalg RSA -keysize 4096 \
	-validity 36500 \
	-storepass "$password" \
	-keypass "$password" \
	-dname "CN=Read Depth"

echo
echo "Created $out. Back it up. Then add these repository secrets:"
echo
echo "  ANDROID_KEYSTORE_PASSWORD  the password you just chose"
echo "  ANDROID_KEY_PASSWORD       the same password"
echo "  ANDROID_KEY_ALIAS          $alias"
echo "  ANDROID_KEYSTORE_BASE64    the output of:  base64 -w0 $out   (macOS: base64 -i $out)"
