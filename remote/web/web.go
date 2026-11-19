// Package web holds the pages otto-remote serves: the phone remote
// (static/) and the "Connect phone" screen the TV shows (tv.html).
package web

import "embed"

//go:embed static tv.html
var Files embed.FS
