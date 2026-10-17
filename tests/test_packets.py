import unittest
import struct

def medtronic_crc16(data: bytes) -> int:
    i2 = 65535
    for b2 in data:
        i3 = (((i2 << 8) | (i2 >> 8)) & 65535) ^ (b2 & 255)
        i4 = i3 ^ ((i3 & 255) >> 4)
        i5 = i4 ^ ((i4 << 12) & 65535)
        i2 = i5 ^ (((i5 & 255) << 5) & 65535)
    return i2 & 65535

class GuardianPacketTest(unittest.TestCase):

    def test_crc16_standard_ccitt_vector(self):
        # Standard CRC-16-CCITT (False) test vector
        self.assertEqual(medtronic_crc16(b"123456789"), 0x29B1)

    def test_packet_1_parsing_and_crc(self):
        ready1 = bytes.fromhex("09 8E 01 2D 9D 72 34 03 A1 5E 71 CF 93 AE 1C 22 D5 15 64 1D")
        data1  = bytes.fromhex("09 8E 76 06 00 00 08 AF 80 01 A6 30 69 68 A5 43 5B 89 8B")
        dec1   = bytes.fromhex("84 42 43 92 30 56 EE E2 B4")

        # 1. Check READY
        self.assertEqual(len(ready1), 20)
        hdr = struct.unpack("<H", ready1[0:2])[0]
        self.assertTrue((hdr & 0x8000) != 0, "Packet must be marked as encrypted")
        expected_crc = struct.unpack("<H", ready1[2:4])[0]
        self.assertEqual(expected_crc, 0x2D01)
        iv = ready1[4:20]
        self.assertEqual(iv.hex(), "9d723403a15e71cf93ae1c22d515641d")

        # 2. Check DATA
        self.assertEqual(len(data1), 19)
        counter = struct.unpack("<I", data1[2:6])[0]
        self.assertEqual(counter, 1654)
        timestamp = struct.unpack("<I", data1[6:10])[0]
        self.assertEqual(timestamp, 25210632)
        ciphertext = data1[10:]
        self.assertEqual(len(ciphertext), 9)

        # 3. Check CRC calculation of decrypted payload
        calc_crc = medtronic_crc16(dec1)
        self.assertEqual(calc_crc, 0x7620)
        self.assertNotEqual(calc_crc, expected_crc, "Payload decrypted with wrong key must fail CRC check")

    def test_packet_2_parsing_and_crc(self):
        ready2 = bytes.fromhex("09 8E 01 2D 9D 72 34 03 A1 5E 71 CF 93 AE 1C 22 D5 15 64 1D")
        data2  = bytes.fromhex("09 8E 77 06 00 00 0B B0 80 01 7A 25 7F 36 D5 8D DA C1 C8")
        dec2   = bytes.fromhex("82 AB 9C 5E 85 1B F3 F0 43")

        self.assertEqual(len(ready2), 20)
        expected_crc = struct.unpack("<H", ready2[2:4])[0]
        self.assertEqual(expected_crc, 0x2D01)

        self.assertEqual(len(data2), 19)
        counter = struct.unpack("<I", data2[2:6])[0]
        self.assertEqual(counter, 1655)
        timestamp = struct.unpack("<I", data2[6:10])[0]
        self.assertEqual(timestamp, 25210891)
        ciphertext = data2[10:]
        self.assertEqual(len(ciphertext), 9)

        calc_crc = medtronic_crc16(dec2)
        self.assertEqual(calc_crc, 0xDA64)
        self.assertNotEqual(calc_crc, expected_crc, "Payload decrypted with wrong key must fail CRC check")

if __name__ == "__main__":
    unittest.main()
