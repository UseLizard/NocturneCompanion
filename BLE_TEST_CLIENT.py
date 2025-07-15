#!/usr/bin/env python3
"""
BLE Test Client for NocturneCompanion
Tests the enhanced BLE implementation with debug features
"""

import asyncio
import json
import sys
from datetime import datetime
from bleak import BleakClient, BleakScanner
from bleak.backends.characteristic import BleakGATTCharacteristic

# Nordic UART Service compatible UUIDs
SERVICE_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
COMMAND_CHAR_UUID = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"  # Write
STATE_CHAR_UUID = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"    # Notify
DEBUG_CHAR_UUID = "6E400004-B5A3-F393-E0A9-E50E24DCCA9E"    # Notify
INFO_CHAR_UUID = "6E400005-B5A3-F393-E0A9-E50E24DCCA9E"     # Read

class NocturneTestClient:
    def __init__(self):
        self.client = None
        self.device = None
        
    async def find_device(self):
        """Find NocturneCompanion device"""
        print("🔍 Scanning for NocturneCompanion devices...")
        devices = await BleakScanner.discover(timeout=10.0)
        
        for device in devices:
            if device.name and "NocturneCompanion" in device.name:
                print(f"✅ Found device: {device.name} ({device.address})")
                return device
            # Also check by service UUID
            if SERVICE_UUID.lower() in [uuid.lower() for uuid in device.metadata.get("uuids", [])]:
                print(f"✅ Found device by service UUID: {device.name or 'Unknown'} ({device.address})")
                return device
                
        print("❌ No NocturneCompanion device found")
        return None
        
    async def connect(self):
        """Connect to the device"""
        self.device = await self.find_device()
        if not self.device:
            return False
            
        print(f"📱 Connecting to {self.device.address}...")
        self.client = BleakClient(self.device.address)
        
        try:
            await self.client.connect()
            print(f"✅ Connected successfully!")
            
            # Print services
            print("\n📋 Available services:")
            for service in self.client.services:
                print(f"  Service: {service.uuid}")
                for char in service.characteristics:
                    props = ", ".join(char.properties)
                    print(f"    Characteristic: {char.uuid} [{props}]")
                    
            return True
        except Exception as e:
            print(f"❌ Connection failed: {e}")
            return False
            
    async def read_device_info(self):
        """Read device capabilities"""
        try:
            data = await self.client.read_gatt_char(INFO_CHAR_UUID)
            info = json.loads(data.decode('utf-8'))
            print("\n📊 Device Capabilities:")
            print(json.dumps(info, indent=2))
        except Exception as e:
            print(f"❌ Failed to read device info: {e}")
            
    def notification_handler(self, char_uuid: str):
        """Create notification handler for a characteristic"""
        def handler(sender: BleakGATTCharacteristic, data: bytearray):
            timestamp = datetime.now().strftime("%H:%M:%S.%f")[:-3]
            try:
                message = json.loads(data.decode('utf-8'))
                
                if char_uuid == STATE_CHAR_UUID:
                    print(f"\n📨 [{timestamp}] State Update:")
                    if message.get("type") == "stateUpdate":
                        print(f"  🎵 {message.get('track', 'Unknown')} - {message.get('artist', 'Unknown')}")
                        print(f"  ▶️  Playing: {message.get('is_playing', False)}")
                        print(f"  🔊 Volume: {message.get('volume_percent', 0)}%")
                    else:
                        print(f"  {json.dumps(message, indent=2)}")
                        
                elif char_uuid == DEBUG_CHAR_UUID:
                    level = message.get("level", "INFO")
                    log_type = message.get("type", "LOG")
                    msg = message.get("message", "")
                    
                    # Color coding for log levels
                    level_symbols = {
                        "ERROR": "❌",
                        "WARNING": "⚠️",
                        "INFO": "ℹ️",
                        "DEBUG": "🔧",
                        "VERBOSE": "📝"
                    }
                    
                    symbol = level_symbols.get(level, "📝")
                    print(f"{symbol} [{timestamp}] {log_type}: {msg}")
                    
                    if message.get("data"):
                        print(f"   Data: {json.dumps(message['data'])}")
                        
            except json.JSONDecodeError:
                print(f"📦 [{timestamp}] Raw data: {data.hex()}")
            except Exception as e:
                print(f"❌ Error handling notification: {e}")
                
        return handler
        
    async def subscribe_notifications(self):
        """Subscribe to notifications"""
        try:
            # Subscribe to state updates
            await self.client.start_notify(STATE_CHAR_UUID, self.notification_handler(STATE_CHAR_UUID))
            print("✅ Subscribed to state updates")
            
            # Subscribe to debug logs
            await self.client.start_notify(DEBUG_CHAR_UUID, self.notification_handler(DEBUG_CHAR_UUID))
            print("✅ Subscribed to debug logs")
            
        except Exception as e:
            print(f"❌ Failed to subscribe: {e}")
            
    async def send_command(self, command: dict):
        """Send a command to the device"""
        try:
            json_str = json.dumps(command)
            print(f"\n📤 Sending command: {json_str}")
            await self.client.write_gatt_char(COMMAND_CHAR_UUID, json_str.encode('utf-8'))
            print("✅ Command sent successfully")
        except Exception as e:
            print(f"❌ Failed to send command: {e}")
            
    async def interactive_mode(self):
        """Interactive command mode"""
        print("\n🎮 Interactive Mode - Available commands:")
        print("  play       - Start playback")
        print("  pause      - Pause playback")
        print("  next       - Next track")
        print("  prev       - Previous track")
        print("  seek <ms>  - Seek to position in milliseconds")
        print("  vol <0-100>- Set volume percentage")
        print("  info       - Read device info")
        print("  quit       - Exit")
        print()
        
        while True:
            try:
                cmd = input("📝 Enter command: ").strip().lower()
                
                if cmd == "quit":
                    break
                elif cmd == "play":
                    await self.send_command({"command": "play"})
                elif cmd == "pause":
                    await self.send_command({"command": "pause"})
                elif cmd == "next":
                    await self.send_command({"command": "next"})
                elif cmd == "prev":
                    await self.send_command({"command": "previous"})
                elif cmd.startswith("seek "):
                    ms = int(cmd.split()[1])
                    await self.send_command({"command": "seek_to", "value_ms": ms})
                elif cmd.startswith("vol "):
                    percent = int(cmd.split()[1])
                    await self.send_command({"command": "set_volume", "value_percent": percent})
                elif cmd == "info":
                    await self.read_device_info()
                else:
                    print("❓ Unknown command")
                    
                # Small delay to see responses
                await asyncio.sleep(0.1)
                
            except KeyboardInterrupt:
                break
            except Exception as e:
                print(f"❌ Error: {e}")
                
    async def run(self):
        """Main run method"""
        if not await self.connect():
            return
            
        try:
            # Read device info
            await self.read_device_info()
            
            # Subscribe to notifications
            await self.subscribe_notifications()
            
            # Small delay to receive initial state
            await asyncio.sleep(1)
            
            # Enter interactive mode
            await self.interactive_mode()
            
        finally:
            if self.client and self.client.is_connected:
                await self.client.disconnect()
                print("\n👋 Disconnected")

async def main():
    print("🚀 NocturneCompanion BLE Test Client")
    print("====================================")
    
    client = NocturneTestClient()
    await client.run()

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\n👋 Goodbye!")