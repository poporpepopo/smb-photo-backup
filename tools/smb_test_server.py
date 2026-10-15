"""A throwaway SMB2 server for testing without the router.

    python3 -m venv /tmp/smbvenv && /tmp/smbvenv/bin/pip install impacket
    mkdir -p /tmp/share && /tmp/smbvenv/bin/python -I tools/smb_test_server.py /tmp/share 4450

Serves <dir> as share "USB" on 127.0.0.1:<port>, user "tester", password "Dummy-Pass-123".
Then either:
  - JVM:      SMB_TEST_PORT=4450 SMB_TEST_ROOT=/tmp/share ./gradlew testDebugUnitTest
  - emulator: adb reverse tcp:445 tcp:4450, and in the app use 127.0.0.1 / USB

impacket differs from Samba in a few replies (e.g. NO_SUCH_FILE for a missing path,
OBJECT_PATH_NOT_FOUND for a missing share), so the router still has the final word.
"""
import binascii
import hashlib
import sys

from impacket import smbserver

root, port = sys.argv[1], int(sys.argv[2])
nt_hash = binascii.hexlify(hashlib.new("md4", "Dummy-Pass-123".encode("utf-16le")).digest()).decode()

server = smbserver.SimpleSMBServer(listenAddress="127.0.0.1", listenPort=port)
server.addShare("USB", root, "test share")
server.setSMB2Support(True)
server.addCredential("tester", 0, "aad3b435b51404eeaad3b435b51404ee", nt_hash)
server.setSMBChallenge("")
server.setLogFile("")
server.start()
