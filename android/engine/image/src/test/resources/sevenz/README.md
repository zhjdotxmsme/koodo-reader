# CB7 / 7z 测试夹具

五个 ~300 B 的 7z 归档，内容完全相同（`pages/1.jpg`、`pages/9.jpg`、`pages/10.jpg`
三个图片条目 + `notes.txt` 一个非图片条目），只差压缩方式。用于
`SevenZExtractorTest`：

| 文件 | 7z 方法 | 覆盖点 |
|---|---|---|
| `cb7-copy.7z` | `-m0=Copy` | 未压缩条目（无解码链） |
| `cb7-lzma2.7z` | `-m0=LZMA2` | 最常见的 7z 方法（依赖 `org.tukaani:xz`） |
| `cb7-lzma.7z` | `-m0=LZMA` | 旧版 7z（LZMA1） |
| `cb7-bcj2.7z` | `-m0=BCJ2 -m1=LZMA2` | 多级过滤器链（x86 BCJ2 + LZMA2） |
| `cb7-encrypted-header.7z` | `-m0=LZMA2 -pS3cret -mhe=on` | 头部加密：**无密码连条目列表都读不出来** |

## 为什么是二进制夹具而不是运行期生成

commons-compress 的 `SevenZOutputFile` 只能写 Copy / LZMA / LZMA2 / DEFLATE / BZIP2，
**写不出 BCJ2 与 AES 头部加密**（只有读支持），而这两种正是验收要求的变体，所以必须
预先生成并随仓库提交（合计约 1.4 KB）。

## 重新生成（需要 7-Zip 命令行）

```powershell
$sevenz = 'C:\Program Files\7-Zip\7z.exe'
$out = 'android/engine/image/src/test/resources/sevenz'
mkdir pages
'PAGE-jpg-1'  > pages/1.jpg
'PAGE-jpg-9'  > pages/9.jpg
'PAGE-jpg-10' > pages/10.jpg
'not an image' > pages/notes.txt
& $sevenz a -t7z -m0=Copy                  "$out/cb7-copy.7z"             'pages/*'
& $sevenz a -t7z -m0=LZMA2                 "$out/cb7-lzma2.7z"            'pages/*'
& $sevenz a -t7z -m0=LZMA                  "$out/cb7-lzma.7z"             'pages/*'
& $sevenz a -t7z -m0=BCJ2 -m1=LZMA2        "$out/cb7-bcj2.7z"             'pages/*'
& $sevenz a -t7z -m0=LZMA2 -pS3cret -mhe=on "$out/cb7-encrypted-header.7z" 'pages/*'
```

（`-p` 的密码与 `-mhe=on` 头部加密一起用；测试只断言「无密码读不出来」这一行为，
不需要正确的密码。）
