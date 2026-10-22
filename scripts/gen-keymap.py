#!/usr/bin/env python3
"""Generates app/src/main/assets/keymap/us.xkb, the US keymap the launcher's
Wayland compositor hands to clients (wl_keyboard.keymap). The compositor sends
evdev key codes; xkb key codes are evdev + 8.

When the rootfs has xkbcli, setup.sh compiles a keymap there and the
compositor prefers that one.
"""
import os

ROWS = {
    'ESC': 1, 'AE01': 2, 'AE02': 3, 'AE03': 4, 'AE04': 5, 'AE05': 6, 'AE06': 7, 'AE07': 8, 'AE08': 9,
    'AE09': 10, 'AE10': 11, 'AE11': 12, 'AE12': 13, 'BKSP': 14, 'TAB': 15,
    'AD01': 16, 'AD02': 17, 'AD03': 18, 'AD04': 19, 'AD05': 20, 'AD06': 21, 'AD07': 22, 'AD08': 23,
    'AD09': 24, 'AD10': 25, 'AD11': 26, 'AD12': 27, 'RTRN': 28, 'LCTL': 29,
    'AC01': 30, 'AC02': 31, 'AC03': 32, 'AC04': 33, 'AC05': 34, 'AC06': 35, 'AC07': 36, 'AC08': 37,
    'AC09': 38, 'AC10': 39, 'AC11': 40, 'TLDE': 41, 'LFSH': 42, 'BKSL': 43,
    'AB01': 44, 'AB02': 45, 'AB03': 46, 'AB04': 47, 'AB05': 48, 'AB06': 49, 'AB07': 50, 'AB08': 51,
    'AB09': 52, 'AB10': 53, 'RTSH': 54, 'KPMU': 55, 'LALT': 56, 'SPCE': 57, 'CAPS': 58,
    'FK01': 59, 'FK02': 60, 'FK03': 61, 'FK04': 62, 'FK05': 63, 'FK06': 64, 'FK07': 65, 'FK08': 66,
    'FK09': 67, 'FK10': 68, 'NMLK': 69, 'SCLK': 70,
    'KP7': 71, 'KP8': 72, 'KP9': 73, 'KPSU': 74, 'KP4': 75, 'KP5': 76, 'KP6': 77, 'KPAD': 78,
    'KP1': 79, 'KP2': 80, 'KP3': 81, 'KP0': 82, 'KPDL': 83, 'LSGT': 86, 'FK11': 87, 'FK12': 88,
    'KPEN': 96, 'RCTL': 97, 'KPDV': 98, 'PRSC': 99, 'RALT': 100, 'HOME': 102, 'UP': 103, 'PGUP': 104,
    'LEFT': 105, 'RGHT': 106, 'END': 107, 'DOWN': 108, 'PGDN': 109, 'INS': 110, 'DELE': 111,
    'MUTE': 113, 'VOL-': 114, 'VOL+': 115, 'POWR': 116, 'KPEQ': 117, 'PAUS': 119,
    'LWIN': 125, 'RWIN': 126, 'COMP': 127,
}

SYMS = {
    'ESC': ['Escape'], 'BKSP': ['BackSpace'], 'TAB': ['Tab', 'ISO_Left_Tab'], 'RTRN': ['Return'],
    'LCTL': ['Control_L'], 'RCTL': ['Control_R'], 'LFSH': ['Shift_L'], 'RTSH': ['Shift_R'],
    'LALT': ['Alt_L'], 'RALT': ['Alt_R'], 'LWIN': ['Super_L'], 'RWIN': ['Super_R'], 'COMP': ['Menu'],
    'SPCE': ['space'], 'CAPS': ['Caps_Lock'], 'NMLK': ['Num_Lock'], 'SCLK': ['Scroll_Lock'],
    'AE01': ['1', 'exclam'], 'AE02': ['2', 'at'], 'AE03': ['3', 'numbersign'], 'AE04': ['4', 'dollar'],
    'AE05': ['5', 'percent'], 'AE06': ['6', 'asciicircum'], 'AE07': ['7', 'ampersand'],
    'AE08': ['8', 'asterisk'], 'AE09': ['9', 'parenleft'], 'AE10': ['0', 'parenright'],
    'AE11': ['minus', 'underscore'], 'AE12': ['equal', 'plus'],
    'AD11': ['bracketleft', 'braceleft'], 'AD12': ['bracketright', 'braceright'],
    'AC10': ['semicolon', 'colon'], 'AC11': ['apostrophe', 'quotedbl'], 'TLDE': ['grave', 'asciitilde'],
    'BKSL': ['backslash', 'bar'], 'AB08': ['comma', 'less'], 'AB09': ['period', 'greater'],
    'AB10': ['slash', 'question'], 'LSGT': ['less', 'greater'],
    'KPMU': ['KP_Multiply'], 'KPSU': ['KP_Subtract'], 'KPAD': ['KP_Add'], 'KPDV': ['KP_Divide'],
    'KPEN': ['KP_Enter'], 'KPEQ': ['KP_Equal'], 'KPDL': ['KP_Delete', 'KP_Decimal'],
    'KP7': ['KP_Home', 'KP_7'], 'KP8': ['KP_Up', 'KP_8'], 'KP9': ['KP_Prior', 'KP_9'],
    'KP4': ['KP_Left', 'KP_4'], 'KP5': ['KP_Begin', 'KP_5'], 'KP6': ['KP_Right', 'KP_6'],
    'KP1': ['KP_End', 'KP_1'], 'KP2': ['KP_Down', 'KP_2'], 'KP3': ['KP_Next', 'KP_3'],
    'KP0': ['KP_Insert', 'KP_0'],
    'PRSC': ['Print'], 'PAUS': ['Pause'], 'HOME': ['Home'], 'UP': ['Up'], 'PGUP': ['Prior'],
    'LEFT': ['Left'], 'RGHT': ['Right'], 'END': ['End'], 'DOWN': ['Down'], 'PGDN': ['Next'],
    'INS': ['Insert'], 'DELE': ['Delete'], 'MUTE': ['XF86AudioMute'],
    'VOL-': ['XF86AudioLowerVolume'], 'VOL+': ['XF86AudioRaiseVolume'], 'POWR': ['XF86PowerOff'],
}
for i in range(1, 13):
    SYMS['FK%02d' % i] = ['F%d' % i]

LETTERS = dict(zip(
    ['AD01', 'AD02', 'AD03', 'AD04', 'AD05', 'AD06', 'AD07', 'AD08', 'AD09', 'AD10',
     'AC01', 'AC02', 'AC03', 'AC04', 'AC05', 'AC06', 'AC07', 'AC08', 'AC09',
     'AB01', 'AB02', 'AB03', 'AB04', 'AB05', 'AB06', 'AB07'],
    'qwertyuiopasdfghjklzxcvbnm'))

TYPES_AND_COMPAT = '''xkb_types "swatonly" {
\ttype "ONE_LEVEL" {
\t\tmodifiers= none;
\t\tlevel_name[Level1]= "Any";
\t};
\ttype "TWO_LEVEL" {
\t\tmodifiers= Shift;
\t\tmap[Shift]= Level2;
\t\tlevel_name[Level1]= "Base";
\t\tlevel_name[Level2]= "Shift";
\t};
\ttype "ALPHABETIC" {
\t\tmodifiers= Shift+Lock;
\t\tmap[Shift]= Level2;
\t\tmap[Lock]= Level2;
\t\tlevel_name[Level1]= "Base";
\t\tlevel_name[Level2]= "Caps";
\t};
\ttype "KEYPAD" {
\t\tmodifiers= Shift+Mod2;
\t\tmap[Mod2]= Level2;
\t\tmap[Shift]= Level2;
\t\tmap[Shift+Mod2]= Level1;
\t\tlevel_name[Level1]= "Base";
\t\tlevel_name[Level2]= "Number";
\t};
};

xkb_compatibility "swatonly" {
\tinterpret.useModMapMods= AnyLevel;
\tinterpret.repeat= False;
\tinterpret Caps_Lock+AnyOfOrNone(all) {
\t\taction= LockMods(modifiers=Lock);
\t};
\tinterpret Num_Lock+AnyOfOrNone(all) {
\t\taction= LockMods(modifiers=Mod2);
\t};
\tinterpret Any+AnyOf(all) {
\t\taction= SetMods(modifiers=modMapMods,clearLocks);
\t};
};
'''

MODMAP = '''\tmodifier_map Shift { <LFSH>, <RTSH> };
\tmodifier_map Lock { <CAPS> };
\tmodifier_map Control { <LCTL>, <RCTL> };
\tmodifier_map Mod1 { <LALT>, <RALT> };
\tmodifier_map Mod2 { <NMLK> };
\tmodifier_map Mod4 { <LWIN>, <RWIN> };
'''


def main():
    out = ['xkb_keymap {', 'xkb_keycodes "swatonly" {', '\tminimum = 8;', '\tmaximum = 255;']
    out += ['\t<%s> = %d;' % (k, v + 8) for k, v in ROWS.items()]
    out += ['};', '', TYPES_AND_COMPAT, 'xkb_symbols "swatonly" {', '\tname[Group1]= "English (US)";']
    for k in ROWS:
        if k in LETTERS:
            out.append('\tkey <%s> { type= "ALPHABETIC", [ %s, %s ] };' % (k, LETTERS[k], LETTERS[k].upper()))
        elif (k.startswith('KP') and k[2:].isdigit()) or k == 'KPDL':
            out.append('\tkey <%s> { type= "KEYPAD", [ %s ] };' % (k, ', '.join(SYMS[k])))
        elif k in SYMS:
            s = SYMS[k]
            out.append('\tkey <%s> { type= "%s", [ %s ] };' % (k, 'TWO_LEVEL' if len(s) == 2 else 'ONE_LEVEL', ', '.join(s)))
    out += [MODMAP + '};', '};']
    dest = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets', 'keymap', 'us.xkb')
    with open(dest, 'w') as f:
        f.write('\n'.join(out) + '\n')


if __name__ == '__main__':
    main()
