package uploader

import (
	"encoding/binary"
	"os"
	"path/filepath"
	"testing"
)

// buildMachOWithUUID crafts a minimal 64-bit Mach-O binary containing a single
// LC_UUID load command — enough for debug/macho to parse. Lets the test run on
// any OS without a real compiled binary fixture.
func buildMachOWithUUID(t *testing.T, uuid [16]byte) []byte {
	t.Helper()
	le := binary.LittleEndian
	buf := make([]byte, 32+24) // mach_header_64 + LC_UUID command
	le.PutUint32(buf[0:], 0xfeedfacf)  // MH_MAGIC_64
	le.PutUint32(buf[4:], 0x0100000c)  // CPU_TYPE_ARM64
	le.PutUint32(buf[8:], 0)           // cpusubtype
	le.PutUint32(buf[12:], 2)          // MH_EXECUTE
	le.PutUint32(buf[16:], 1)          // ncmds
	le.PutUint32(buf[20:], 24)         // sizeofcmds
	le.PutUint32(buf[24:], 0)          // flags
	le.PutUint32(buf[28:], 0)          // reserved
	le.PutUint32(buf[32:], 0x1b)       // LC_UUID
	le.PutUint32(buf[36:], 24)         // cmdsize
	copy(buf[40:], uuid[:])
	return buf
}

var testUUID = [16]byte{
	0x0a, 0x1b, 0x2c, 0x3d, 0x4e, 0x5f, 0x60, 0x71,
	0x82, 0x93, 0xa4, 0xb5, 0xc6, 0xd7, 0xe8, 0xf9,
}

const testUUIDCanonical = "0a1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9"

func TestDSYMUUIDFromMachOFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "MyApp")
	if err := os.WriteFile(path, buildMachOWithUUID(t, testUUID), 0o644); err != nil {
		t.Fatal(err)
	}

	uuids, err := DSYMUUIDs(path)
	if err != nil {
		t.Fatalf("DSYMUUIDs: %v", err)
	}
	if len(uuids) != 1 || uuids[0] != testUUIDCanonical {
		t.Errorf("uuids = %v, want [%s]", uuids, testUUIDCanonical)
	}
}

func TestDSYMUUIDResolvesDSYMBundleDirectory(t *testing.T) {
	// Real Xcode layout: MyApp.dSYM/Contents/Resources/DWARF/MyApp
	root := filepath.Join(t.TempDir(), "MyApp.dSYM")
	dwarfDir := filepath.Join(root, "Contents", "Resources", "DWARF")
	if err := os.MkdirAll(dwarfDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dwarfDir, "MyApp"), buildMachOWithUUID(t, testUUID), 0o644); err != nil {
		t.Fatal(err)
	}

	uuids, err := DSYMUUIDs(root)
	if err != nil {
		t.Fatalf("DSYMUUIDs: %v", err)
	}
	if len(uuids) != 1 || uuids[0] != testUUIDCanonical {
		t.Errorf("uuids = %v, want [%s]", uuids, testUUIDCanonical)
	}
}

func TestDSYMUUIDErrorsOnNonMachOFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "not-macho")
	if err := os.WriteFile(path, []byte("hello"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := DSYMUUIDs(path); err == nil {
		t.Fatal("expected error for non-Mach-O file")
	}
}
