package main

import (
	"embed"
	"fmt"
	"io/fs"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"runtime"
	"time"
)

//go:embed assets/index.html assets/css/style.css assets/js/app.js assets/manifest.json assets/icon.png
var assets embed.FS

func main() {
	// 从 embed.FS 提取 assets 子目录
	subFS, err := fs.Sub(assets, "assets")
	if err != nil {
		log.Fatal("无法加载资源:", err)
	}

	// 找可用端口
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		log.Fatal("无法绑定端口:", err)
	}
	port := listener.Addr().(*net.TCPAddr).Port

	// HTTP 服务器
	server := &http.Server{
		Handler: http.FileServer(http.FS(subFS)),
	}
	go server.Serve(listener)

	url := fmt.Sprintf("http://127.0.0.1:%d", port)
	fmt.Printf("TV分屏控制端已启动: %s\n", url)
	fmt.Println("请勿关闭此窗口")

	// 自动打开浏览器
	time.Sleep(500 * time.Millisecond)
	openBrowser(url)

	// 等待 Ctrl+C
	select {}
}

func openBrowser(url string) {
	var cmd string
	var args []string

	switch runtime.GOOS {
	case "windows":
		cmd = "cmd"
		args = []string{"/c", "start", url}
	case "darwin":
		cmd = "open"
		args = []string{url}
	default: // linux
		cmd = "xdg-open"
		args = []string{url}
	}

	if err := exec.Command(cmd, args...).Start(); err != nil {
		fmt.Printf("无法自动打开浏览器: %v\n", err)
		fmt.Printf("请手动访问: %s\n", url)

		// 输出 URL 到文件（双击运行时可以看到）
		f, _ := os.Create("TV分屏控制_访问地址.txt")
		defer f.Close()
		fmt.Fprintf(f, "请用浏览器打开: %s\n", url)
	}
}
