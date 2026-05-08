// Package tun2socksmobile — тонкая обёртка над xjasonlyu/tun2socks для
// сборки в Android .aar через gomobile bind. Из Kotlin вызывается
// dnsttmobile.Tun2socksmobile.start(fd, "socks5://127.0.0.1:1080", 1500).
//
// Почему именно gomobile (а не запуск как subprocess):
// JVM-ProcessBuilder не передаёт произвольные file descriptors дочернему
// процессу — fd, полученный через VpnService.Builder.establish().detachFd()
// бесполезен для отдельного бинарника. С gomobile-биндингом tun2socks
// работает в том же процессе APK, fd проходит как обычный int.
package tun2socksmobile

import (
	"errors"
	"sync"

	_ "github.com/xjasonlyu/tun2socks/v2/dns"
	"github.com/xjasonlyu/tun2socks/v2/engine"

	// gobind в gomobile bind ищет этот пакет. Blank import не даёт
	// `go mod tidy` его удалить, и при этом не запускает init.
	_ "golang.org/x/mobile/bind"
)

var (
	mu      sync.Mutex
	running bool
)

// Start поднимает tun2socks.
//
// fd       — TUN-дескриптор от VpnService.Builder.establish().detachFd().
//             Используем int32, чтобы gomobile сгенерировал Java-сигнатуру
//             с типом int (а не long на 64-битных Android), и Kotlin
//             передавал tunFd напрямую без приведения.
// proxyURL — URL upstream-прокси (наш dnstt-client SOCKS5/HTTP),
//             например "socks5://127.0.0.1:1080".
// mtu      — MTU интерфейса (рекомендуется 1500 для Android VPN).
func Start(fd int32, proxyURL string, mtu int32) error {
	mu.Lock()
	defer mu.Unlock()
	if running {
		return errors.New("already running")
	}
	if fd <= 0 {
		return errors.New("invalid fd")
	}
	if proxyURL == "" {
		return errors.New("empty proxy URL")
	}
	if mtu <= 0 {
		mtu = 1500
	}

	key := &engine.Key{
		Device:   "fd://" + itoa(int(fd)),
		Proxy:    proxyURL,
		LogLevel: "warning",
		MTU:      int(mtu),
	}
	engine.Insert(key)
	// engine.Start() в апстрим-tun2socks при ошибке вызывает log.Fatalf,
	// который через os.Exit(1) убивает весь процесс APK. Мы используем
	// форк-вариант StartE, возвращающий error, чтобы Kotlin мог поймать
	// сбой и не уронить приложение.
	if err := engine.StartE(); err != nil {
		return err
	}
	running = true
	return nil
}

// Stop корректно останавливает tun2socks. Идемпотентно.
func Stop() {
	mu.Lock()
	defer mu.Unlock()
	if !running {
		return
	}
	// форк-вариант не вызывает log.Fatalf при сбое
	_ = engine.StopE()
	running = false
}

// IsRunning возвращает true, если tun2socks-engine активен.
func IsRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return running
}

// itoa без зависимости от strconv (gomobile не любит лишние пакеты).
func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	neg := n < 0
	if neg {
		n = -n
	}
	var buf [20]byte
	i := len(buf)
	for n > 0 {
		i--
		buf[i] = byte('0' + n%10)
		n /= 10
	}
	if neg {
		i--
		buf[i] = '-'
	}
	return string(buf[i:])
}
