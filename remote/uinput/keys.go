package uinput

// Linux key codes (<linux/input-event-codes.h>) we send. Android maps them
// through Generic.kl: KeyBack becomes BACK, KeyHomepage HOME, KeySelect
// DPAD_CENTER, and so on.
const (
	KeyEsc        = 1
	Key1          = 2
	Key2          = 3
	Key3          = 4
	Key4          = 5
	Key5          = 6
	Key6          = 7
	Key7          = 8
	Key8          = 9
	Key9          = 10
	Key0          = 11
	KeyMinus      = 12
	KeyEqual      = 13
	KeyBackspace  = 14
	KeyTab        = 15
	KeyQ          = 16
	KeyW          = 17
	KeyE          = 18
	KeyR          = 19
	KeyT          = 20
	KeyY          = 21
	KeyU          = 22
	KeyI          = 23
	KeyO          = 24
	KeyP          = 25
	KeyLeftBrace  = 26
	KeyRightBrace = 27
	KeyEnter      = 28
	KeyLeftCtrl   = 29
	KeyA          = 30
	KeyS          = 31
	KeyD          = 32
	KeyF          = 33
	KeyG          = 34
	KeyH          = 35
	KeyJ          = 36
	KeyK          = 37
	KeyL          = 38
	KeySemicolon  = 39
	KeyApostrophe = 40
	KeyGrave      = 41
	KeyLeftShift  = 42
	KeyBackslash  = 43
	KeyZ          = 44
	KeyX          = 45
	KeyC          = 46
	KeyV          = 47
	KeyB          = 48
	KeyN          = 49
	KeyM          = 50
	KeyComma      = 51
	KeyDot        = 52
	KeySlash      = 53
	KeyLeftAlt    = 56
	KeySpace      = 57
	KeyUp         = 103
	KeyLeft       = 105
	KeyRight      = 106
	KeyDown       = 108
	KeyMute       = 113
	KeyVolumeDown = 114
	KeyVolumeUp   = 115
	KeyPower      = 116
	KeyMenu       = 139
	KeyBack       = 158
	KeyNextSong   = 163
	KeyPlayPause  = 164
	KeyPrevSong   = 165
	KeyRewind     = 168
	KeyHomepage   = 172
	KeyFastFwd    = 208
	KeySelect     = 353

	BtnLeft   = 0x110
	BtnRight  = 0x111
	BtnMiddle = 0x112
)
