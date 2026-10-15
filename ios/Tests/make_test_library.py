"""
生成一套模拟器用的“假相册”，覆盖各个清理页面：
  博物馆（每张都不一样 + 一件展品拍了 3 张 + 2 张糊的）、海边连拍（同一个画面 15 张）、
  相似照片（3 组连拍）、压缩副本（同一张缩小存回来）、刚拍的（今天）、截图、
  视频（4K 高码率带声音 → 可压缩；1080p 长视频 → 不可压缩；2 秒短视频；重复保存的视频）。
照片底图用模拟器自带的 5 张示例照片裁剪、调色、翻转，再加上画出来的图形。
用法：python3 make_test_library.py <示例照片目录> <输出目录>
"""
import os, random, subprocess, sys
from datetime import datetime, timedelta
from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageFont, ImageOps
from PIL.TiffImagePlugin import IFDRational

SRC, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)
random.seed(7)
bases = [Image.open(os.path.join(SRC, f)).convert("RGB") for f in sorted(os.listdir(SRC)) if f.upper().endswith(".JPG")]
W, H = 4032, 3024
FONT = "/System/Library/Fonts/PingFang.ttc" if os.path.exists("/System/Library/Fonts/PingFang.ttc") else "/System/Library/Fonts/STHeiti Light.ttc"
made = []

def dms(v):
    d = int(v); m = int((v - d) * 60); s = round(((v - d) * 60 - m) * 60 * 100)
    return (IFDRational(d, 1), IFDRational(m, 1), IFDRational(s, 100))

def save(im, name, when, gps=None, quality=92):
    exif = Image.Exif()
    exif[0x010F] = "Apple"; exif[0x0110] = "iPhone 15"
    ts = when.strftime("%Y:%m:%d %H:%M:%S")
    # 子目录要整个赋值成 dict 才会写进文件（get_ifd 改了不写）
    exif[0x8769] = {0x9003: ts, 0x9004: ts, 0x9011: "+08:00"}
    if gps:
        exif[0x8825] = {1: "N", 2: dms(gps[0]), 3: "E", 4: dms(gps[1])}
    p = os.path.join(OUT, name)
    im.save(p, "JPEG", quality=quality, exif=exif)
    made.append(p)

def distinct(i):
    """第 i 张“不一样”的照片：不同底图的不同区域 + 不同调色 + 画上不同的东西"""
    b = bases[i % len(bases)]
    bw, bh = b.size
    cw, ch = int(bw * 0.45), int(bh * 0.45)
    x = [0, bw - cw, 0, bw - cw, (bw - cw) // 2][(i // len(bases)) % 5]
    y = [0, 0, bh - ch, bh - ch, (bh - ch) // 2][(i // len(bases)) % 5]
    im = b.crop((x, y, x + cw, y + ch)).resize((W, H))
    if i % 3 == 1: im = ImageOps.mirror(im)
    if i % 4 == 2: im = ImageOps.flip(im)
    im = ImageEnhance.Color(im).enhance(0.6 + (i % 5) * 0.25)
    d = ImageDraw.Draw(im)
    r = random.Random(i)
    for _ in range(3):  # “展品”：几个大色块，让每张的画面结构都不同
        x0, y0 = r.randint(0, W - 1400), r.randint(0, H - 1200)
        d.rounded_rectangle((x0, y0, x0 + r.randint(600, 1400), y0 + r.randint(500, 1200)), 60,
                            fill=(r.randint(0, 255), r.randint(0, 255), r.randint(0, 255)))
    return im

def shifted(im, k):
    """同一个画面换一点角度：平移 + 轻微亮度变化"""
    dx, dy = 40 * k, 25 * k
    out = im.crop((dx, dy, W, H)).resize((W, H))
    return ImageEnhance.Brightness(out).enhance(1 + 0.02 * k)

MUSEUM = (31.2304, 121.4737); BEACH = (18.2528, 109.5119); HOME = (30.5728, 104.0668)

# 1. 博物馆：25 件不同展品，第 5 件拍了 3 张，另有 2 张拍糊
t = datetime(2026, 7, 21, 11, 0)
for i in range(25):
    im = distinct(i)
    save(im, f"museum_{i:02d}.jpg", t, MUSEUM); t += timedelta(minutes=2)
    if i == 5:
        save(shifted(im, 1), "museum_05_b.jpg", t, MUSEUM); t += timedelta(seconds=20)
        save(shifted(im, 2), "museum_05_c.jpg", t, MUSEUM); t += timedelta(seconds=20)
for k in range(2):
    save(distinct(40 + k).filter(ImageFilter.GaussianBlur(40)), f"museum_blurry_{k}.jpg", t, MUSEUM); t += timedelta(minutes=1)

# 2. 海边：同一个画面拍了 15 张
base = distinct(60)
t = datetime(2026, 8, 10, 17, 0)
for k in range(15):
    save(shifted(base, k % 5), f"beach_{k:02d}.jpg", t, BEACH); t += timedelta(seconds=40)

# 3. 相似照片：3 组连拍（每组 3 张，几秒内），分在不同的天
for g in range(3):
    t = datetime(2026, 9, 1 + g * 5, 10, 0)
    im = distinct(80 + g)
    for k in range(3):
        save(shifted(im, k), f"burst{g}_{k}.jpg", t, HOME); t += timedelta(seconds=2)

# 4. 压缩副本：一张原图 + 一份缩小、压得很狠的副本（像从微信存回来的）
orig = distinct(90)
save(orig, "copy_original.jpg", datetime(2026, 8, 24, 17, 48), HOME)
small = orig.resize((1440, 1080))
save(small, "copy_wechat.jpg", datetime(2026, 8, 25, 9, 12), None, quality=60)

# 5. 刚拍的：今天 6 张，其中 1 张糊
t = datetime(2026, 10, 15, 21, 0)
for k in range(6):
    im = distinct(100 + k)
    if k == 3: im = im.filter(ImageFilter.GaussianBlur(35))
    save(im, f"today_{k}.jpg", t, HOME); t += timedelta(minutes=3)

# 6. 截图：聊天记录样子的 PNG（EXIF 里标 Screenshot）
font = ImageFont.truetype(FONT, 44)
for k in range(3):
    im = Image.new("RGB", (1179, 2556), (237, 237, 237))
    d = ImageDraw.Draw(im)
    d.rectangle((0, 0, 1179, 220), fill=(247, 247, 247)); d.text((480, 140), "家长群", fill=(0, 0, 0), font=font)
    for j in range(6):
        y = 300 + j * 330
        if j % 2: d.rounded_rectangle((300, y, 1040, y + 200), 20, fill=(149, 236, 105)); d.text((340, y + 70), f"收到，谢谢老师 {k}{j}", fill=(0, 0, 0), font=font)
        else: d.rounded_rectangle((140, y, 900, y + 200), 20, fill=(255, 255, 255)); d.text((180, y + 70), f"明天带水彩笔和画纸 {k}{j}", fill=(0, 0, 0), font=font)
    exif = Image.Exif(); exif[0x8769] = {0x9286: "Screenshot"}
    p = os.path.join(OUT, f"shot_{k}.png"); im.save(p, "PNG", exif=exif); made.append(p)

# 7. 视频
def video(name, size, secs, bitrate, when, fps=30):
    p = os.path.join(OUT, name)
    iso = (when - timedelta(hours=8)).strftime("%Y-%m-%dT%H:%M:%S.000000Z")
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error",
                    "-f", "lavfi", "-i", f"testsrc2=size={size}:rate={fps}:duration={secs}",
                    "-f", "lavfi", "-i", f"sine=frequency=440:duration={secs}",
                    "-c:v", "libx264", "-preset", "ultrafast", "-b:v", bitrate, "-maxrate", bitrate, "-bufsize", bitrate,
                    "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "128k",
                    "-metadata", f"creation_time={iso}", "-movflags", "+faststart", p], check=True)
    made.append(p)
    return p

video("v_4k_dense.mp4", "3840x2160", 12, "45M", datetime(2026, 9, 20, 15, 0))
video("v_1080p_long.mp4", "1920x1080", 40, "6M", datetime(2026, 9, 21, 15, 0))
video("v_720p_short.mp4", "1280x720", 2, "3M", datetime(2026, 9, 22, 15, 0))
dup = video("v_1080p_dup.mp4", "1920x1080", 8, "8M", datetime(2026, 9, 23, 15, 0))
import shutil; p2 = os.path.join(OUT, "v_1080p_dup_copy.mp4"); shutil.copy(dup, p2); made.append(p2)

print(len(made), "files")
