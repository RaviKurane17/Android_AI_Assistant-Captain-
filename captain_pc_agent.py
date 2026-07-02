# Captain AI - Windows PC Agent
# Save this file as captain_pc_agent.py and run it on your Windows PC
#
# ─── SETUP ────────────────────────────────────────────────────────────────────
# 1. Copy .env.example → .env  and fill in your real values
# 2. Run: pip install firebase-admin pyautogui psutil python-dotenv
# 3. Place your Firebase service account JSON at the path set in .env
# ──────────────────────────────────────────────────────────────────────────────

import json, os, subprocess, time, threading, ctypes, sys

# Load .env file for local secrets (this file is gitignored — never committed)
try:
    from dotenv import load_dotenv
    load_dotenv()
except ImportError:
    print('[WARNING] python-dotenv not installed. Run: pip install python-dotenv')

try:
    import firebase_admin
    from firebase_admin import credentials, db as firebase_db
    FIREBASE_OK = True
except ImportError:
    print('[ERROR] Run: pip install firebase-admin')
    FIREBASE_OK = False

try:
    import pyautogui
    PYAUTOGUI_OK = True
except ImportError:
    print('[WARNING] Run: pip install pyautogui')
    PYAUTOGUI_OK = False

try:
    import psutil
    PSUTIL_OK = True
except ImportError:
    PSUTIL_OK = False

# ─── CONFIG (loaded from .env — never hardcode real values here) ──────────────
FIREBASE_CREDENTIALS_PATH = os.getenv('FIREBASE_CREDENTIALS_PATH', 'firebase_service_account.json')
FIREBASE_DATABASE_URL      = os.getenv('FIREBASE_DATABASE_URL', 'https://YOUR_PROJECT_ID-default-rtdb.firebaseio.com/')
PC_DEVICE_ID               = os.getenv('PC_DEVICE_ID', 'PC')
# ─────────────────────────────────────────────────────────────────────────────


APP_MAP = {
    'notepad': 'notepad.exe',
    'calculator': 'calc.exe',
    'paint': 'mspaint.exe',
    'file explorer': 'explorer.exe',
    'explorer': 'explorer.exe',
    'task manager': 'taskmgr.exe',
    'taskmgr': 'taskmgr.exe',
    'cmd': 'cmd.exe',
    'command prompt': 'cmd.exe',
    'powershell': 'powershell.exe',
    'chrome': 'chrome.exe',
    'google chrome': 'chrome.exe',
    'firefox': 'firefox.exe',
    'edge': 'msedge.exe',
    'microsoft edge': 'msedge.exe',
    'vs code': 'code.exe',
    'visual studio code': 'code.exe',
    'vscode': 'code.exe',
    'word': 'WINWORD.EXE',
    'excel': 'EXCEL.EXE',
    'powerpoint': 'POWERPNT.EXE',
    'outlook': 'OUTLOOK.EXE',
    'teams': 'Teams.exe',
    'spotify': 'Spotify.exe',
    'vlc': 'vlc.exe',
    'discord': 'Discord.exe',
    'steam': 'Steam.exe',
    'whatsapp': 'WhatsApp.exe',
    'telegram': 'Telegram.exe',
    'snipping tool': 'SnippingTool.exe',
    'snip': 'SnippingTool.exe',
    'control panel': 'control.exe',
    'device manager': 'devmgmt.msc',
    'regedit': 'regedit.exe',
    'settings': 'ms-settings:',
    'android studio': 'studio64.exe',
    'brave': 'brave.exe',
    'zoom': 'Zoom.exe',
}

def execute_pc_command(instruction):
    instr = instruction.lower().strip()
    print(f'[CAPTAIN PC] Executing: {instruction}')

    if instr.startswith('open ') or instr.startswith('launch ') or instr.startswith('start '):
        app = instr.replace('open ','').replace('launch ','').replace('start ','').strip()
        return open_application(app)

    if instr.startswith('close ') or instr.startswith('kill '):
        app = instr.replace('close ','').replace('kill ','').strip()
        return close_application(app)

    if 'shutdown' in instr or 'shut down' in instr:
        os.system('shutdown /s /t 30')
        return 'PC shutting down in 30 seconds.'

    if 'restart' in instr or 'reboot' in instr:
        os.system('shutdown /r /t 30')
        return 'PC restarting in 30 seconds.'

    if 'cancel shutdown' in instr:
        os.system('shutdown /a')
        return 'Shutdown cancelled.'

    if 'lock' in instr:
        ctypes.windll.user32.LockWorkStation()
        return 'Screen locked.'

    if 'screenshot' in instr:
        return take_screenshot()

    if 'volume' in instr:
        if 'mute' in instr:
            if PYAUTOGUI_OK: pyautogui.press('volumemute')
            return 'Muted.'
        elif 'up' in instr or 'increase' in instr or 'louder' in instr:
            if PYAUTOGUI_OK:
                for _ in range(5): pyautogui.press('volumeup')
            return 'Volume increased.'
        elif 'down' in instr or 'decrease' in instr or 'lower' in instr:
            if PYAUTOGUI_OK:
                for _ in range(5): pyautogui.press('volumedown')
            return 'Volume decreased.'
        elif 'max' in instr or 'full' in instr:
            if PYAUTOGUI_OK:
                for _ in range(20): pyautogui.press('volumeup')
            return 'Volume maximized.'

    if instr.startswith('type:') or instr.startswith('type '):
        text = instruction.replace('type:','').replace('type ','',1).strip()
        if PYAUTOGUI_OK:
            time.sleep(0.5)
            pyautogui.typewrite(text, interval=0.05)
            return f'Typed: {text}'

    if instr.startswith('search ') or instr.startswith('google '):
        query = instr.replace('search ','').replace('google ','').strip()
        import webbrowser
        webbrowser.open(f'https://www.google.com/search?q={query}')
        return f'Searched: {query}'

    if instr.startswith('go to ') or instr.startswith('open url '):
        url = instr.replace('go to ','').replace('open url ','').strip()
        if not url.startswith('http'): url = 'https://' + url
        import webbrowser
        webbrowser.open(url)
        return f'Opened: {url}'

    shortcut_map = {
        'minimize all': ('win','d'), 'show desktop': ('win','d'),
        'task view': ('win','tab'), 'close window': ('alt','f4'),
        'switch window': ('alt','tab'), 'copy': ('ctrl','c'),
        'paste': ('ctrl','v'), 'undo': ('ctrl','z'), 'save': ('ctrl','s'),
        'select all': ('ctrl','a'), 'open task manager': ('ctrl','shift','esc'),
    }
    for phrase, keys in shortcut_map.items():
        if phrase in instr:
            if PYAUTOGUI_OK: pyautogui.hotkey(*keys)
            return f'Executed: {phrase}'

    try:
        subprocess.Popen(instruction, shell=True)
        return f'Attempted: {instruction}'
    except Exception as e:
        return f'Unknown command: {instruction}'

def open_application(app_name):
    exe = None
    for key, value in APP_MAP.items():
        if key in app_name:
            exe = value
            break
    if exe:
        try:
            if exe.startswith('ms-settings:'):
                os.startfile(exe)
            else:
                subprocess.Popen(exe, shell=True)
            return f'Opened {app_name}.'
        except Exception as e:
            return f'Failed: {str(e)}'
    else:
        try:
            subprocess.Popen(app_name, shell=True)
            return f'Tried to open: {app_name}'
        except Exception as e:
            return f'Not found: {app_name}'

def close_application(app_name):
    if PSUTIL_OK:
        closed = False
        for proc in psutil.process_iter(['name','pid']):
            if app_name.lower() in proc.info['name'].lower():
                proc.kill()
                closed = True
        return f'Closed {app_name}.' if closed else f'Not running: {app_name}'
    else:
        os.system(f'taskkill /f /im {app_name}.exe 2>nul')
        return f'Kill attempted: {app_name}'

def take_screenshot():
    try:
        if PYAUTOGUI_OK:
            desktop = os.path.join(os.path.expanduser('~'), 'Desktop')
            path = os.path.join(desktop, f'captain_ss_{int(time.time())}.png')
            pyautogui.screenshot(path)
            return f'Screenshot saved to Desktop.'
        else:
            if PYAUTOGUI_OK: pyautogui.hotkey('win','prtsc')
            return 'Screenshot taken.'
    except Exception as e:
        return f'Screenshot failed: {str(e)}'

db_ref = None

def initialize_firebase():
    global db_ref
    if not FIREBASE_OK: return False
    if not os.path.exists(FIREBASE_CREDENTIALS_PATH):
        print(f'[ERROR] Missing: {FIREBASE_CREDENTIALS_PATH}')
        print('Download from Firebase Console -> Project Settings -> Service Accounts -> Generate New Private Key')
        return False
    try:
        cred = credentials.Certificate(FIREBASE_CREDENTIALS_PATH)
        firebase_admin.initialize_app(cred, {'databaseURL': FIREBASE_DATABASE_URL})
        db_ref = firebase_db.reference('commands')
        print('[OK] Firebase connected!')
        return True
    except Exception as e:
        print(f'[ERROR] Firebase: {e}')
        return False

def poll_and_execute():
    print('[CAPTAIN PC] Polling for commands every 2 seconds...')
    while True:
        try:
            snap = db_ref.get()
            if snap:
                for cmd_id, cmd_data in snap.items():
                    if not isinstance(cmd_data, dict): continue
                    if cmd_data.get('targetDeviceId') == PC_DEVICE_ID and cmd_data.get('status') == 'pending':
                        msg = cmd_data.get('message','')
                        action = cmd_data.get('action','voice_command')
                        print(f'\n[NEW COMMAND] {action}: {msg}')
                        db_ref.child(cmd_id).update({'status': 'processing'})
                        try:
                            result = execute_pc_command(msg if action == 'voice_command' else f'{action} {msg}')
                            db_ref.child(cmd_id).update({'status':'success','result':result,'executedAt':int(time.time()*1000)})
                            print(f'[DONE] {result}')
                        except Exception as e:
                            db_ref.child(cmd_id).update({'status':'error','result':str(e),'executedAt':int(time.time()*1000)})
        except Exception as e:
            print(f'[POLL ERROR] {e}')
        time.sleep(2)

def update_pc_stats():
    if not PSUTIL_OK: return
    stats = firebase_db.reference('pc_stats').child(PC_DEVICE_ID)
    while True:
        try:
            cpu = psutil.cpu_percent(interval=1)
            ram = psutil.virtual_memory()
            stats.set({'cpu':f'{cpu:.0f}%','ram':f'{ram.percent:.0f}%','status':'online','lastSeen':int(time.time()*1000)})
        except: pass
        time.sleep(5)

if __name__ == '__main__':
    print('='*55)
    print('  CAPTAIN AI - Windows PC Agent')
    print('  Listening for commands from your phone...')
    print('='*55)
    if not initialize_firebase():
        print('\nSETUP:')
        print('1. Place firebase_service_account.json in this folder')
        print('2. Edit FIREBASE_DATABASE_URL in this script')
        print('3. Run: pip install firebase-admin pyautogui psutil')
        input('\nPress Enter to exit...')
        sys.exit(1)
    print('\n[ONLINE] Say: open notepad on my PC')
    threading.Thread(target=update_pc_stats, daemon=True).start()
    try:
        poll_and_execute()
    except KeyboardInterrupt:
        print('\n[STOPPED] Captain PC Agent offline.')
