import Photos
import SwiftUI

@main
struct QingleApp: App {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var phase

    init() {
        Usage.launched()
        Usage.watchMainThread()
        Diag.start()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .tint(.accentText)
                .onAppear { model.start(); Usage.foreground(true) }
                .onChange(of: phase) { _, p in
                    if p == .active { model.start() }
                    // 记下是不是在前台：下次打开时还是“在前台”，说明上次是闪退
                    if p != .inactive { Usage.foreground(p == .active) }
                }
        }
    }
}

struct RootView: View {
    @EnvironmentObject var model: AppModel
    @State private var path: [Route] = []

    var body: some View {
        ZStack(alignment: .bottom) {
            if model.authorized {
                NavigationStack(path: $path) {
                    HomeView(path: $path)
                        .navigationDestination(for: Route.self) { r in
                            switch r {
                            case .similar: SimilarView()
                            case .scenes: SceneListView()
                            case .bad: GridReviewView(kind: .bad)
                            case .screenshots: GridReviewView(kind: .screenshots)
                            case .bigVideos: GridReviewView(kind: .bigVideos)
                            case .settings: SettingsView()
                            case .recent: RecentView()
                            case .aesthetic: AestheticView()
                            case .videos: GridReviewView(kind: .videos)
                            case .videoScenes: VideoSceneView()
                            case .compress: CompressView()
                            case .docs: DocsView()
                            case .notes: NotesView()
                            case .report: ReportView()
                            }
                        }
                }
            } else {
                WelcomeView()
            }
            if let t = model.toast {
                Text(t).font(.subheadline).foregroundStyle(.white).multilineTextAlignment(.center)
                    .padding(.horizontal, 18).padding(.vertical, 12)
                    .background(Color(white: 0.11, opacity: 0.92), in: RoundedRectangle(cornerRadius: 16))
                    .padding(.horizontal, 28).padding(.bottom, 90)
                    .transition(.opacity)
                    .onTapGesture { model.toast = nil }
            }
        }
        .animation(.easeInOut(duration: 0.2), value: model.toast)
        // 每次相册整理好，按能腾出多少重新预约每月提醒
        .onReceive(model.$library) { lib in model.updateReminder(lib) }
        #if DEBUG
        // 模拟器测试用：-route similar 直接打开某一页
        .onAppear {
            let args = CommandLine.arguments
            if let i = args.firstIndex(of: "-route"), i + 1 < args.count, let r = Route(rawValue: args[i + 1]) { path = [r] }
            // -reminderTest：不等相册整理，先用一组示例数字预约，15 秒后弹每月提醒
            if args.contains("-reminderTest") { MonthlyReminder.update(count: 128, bytes: 3_276_800_000) { model.show("每月提醒你一次能腾出多少空间，请允许通知（设置里随时能关）") } }
        }
        #endif
    }
}

/// 第一次打开：介绍 + 要相册权限
struct WelcomeView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            BrandMark(size: 96)
            Text("欢迎使用轻了相册").font(.title.weight(.bold)).padding(.top, 24)
            Text("清了没？相册轻了，心也轻了").font(.subheadline).foregroundStyle(.secondary).padding(.top, 4)
            VStack(alignment: .leading, spacing: 20) {
                feature("photo.stack", .brand, "每组只留最好的一张", "连拍、重复保存的照片，自动挑出最清楚、最好看的那张")
                feature("sparkles", .brandYellow, "每个场景留几张精华", "同一个地方拍了几十张？帮你挑 3–5 张不重复的")
                feature("lock.fill", .brand, "照片不出手机", "全在手机里分析，不上传。删除前系统都会问你，30 天内能恢复")
            }
            .padding(.top, 36)
            Spacer(minLength: 30)
            Text(model.auth == .denied || model.auth == .restricted ? "之前没允许访问相册，去设置里打开" : "需要允许访问照片和视频")
                .font(.footnote).foregroundStyle(.secondary)
            PrimaryButton(title: "继续") {
                if model.auth == .notDetermined {
                    model.requestAccess()
                } else if let u = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(u)
                }
            }
            .padding(.top, 12).padding(.bottom, 16)
        }
        .padding(.horizontal, 32)
        .background(Color.page)
    }

    private func feature(_ icon: String, _ color: Color, _ title: String, _ text: String) -> some View {
        HStack(alignment: .center, spacing: 16) {
            Image(systemName: icon).font(.system(size: 28)).foregroundStyle(color).frame(width: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline)
                Text(text).font(.subheadline).foregroundStyle(.secondary)
            }
        }
    }
}

struct SettingsView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.scenePhase) private var phase
    @State private var askReanalyze = false
    @AppStorage(MonthlyReminder.key) private var monthly = true
    @State private var notifDenied = false

    var body: some View {
        List {
            Section {
                Toggle(isOn: $monthly) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("每月提醒我清理")
                        Text("能腾出 500 MB 以上时发一条通知").font(.footnote).foregroundStyle(.secondary)
                    }
                }
                .tint(.brand)
                .onChange(of: monthly) { _, _ in model.updateReminder(model.library) }
                if monthly && notifDenied {
                    Button("通知没打开，去系统设置里允许") {
                        if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) }
                    }
                    .font(.footnote)
                }
            }
            .task(id: phase) { notifDenied = await MonthlyReminder.denied() }
            Section {
                Button("让保留过的照片重新参与建议") {
                    Usage.add("让保留过的重新参与建议")
                    Store.shared.clearReviewed()
                    model.refresh()
                    model.show("保留过的照片会重新出现在建议里")
                }
                Button("重新分析全部照片", role: .destructive) { askReanalyze = true }
            }
            Section {
                NavigationLink(value: Route.report) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("发送使用报告")
                        Text("帮作者改进：只有使用次数，没有照片").font(.footnote).foregroundStyle(.secondary)
                    }
                }
            } header: {
                Text("参与测试")
            }
            Section {
                Text("轻了相册 \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "")\n所有分析都在手机里完成，照片不会离开手机。删除都要经过系统确认，删掉的在「照片」的「最近删除」里放 30 天。\n美感评分用的是 iOS 自带的图像美感评估。")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("设置")
        .usagePage("设置")
        .navigationBarTitleDisplayMode(.inline)
        .alert("重新分析全部照片？", isPresented: $askReanalyze) {
            Button("取消", role: .cancel) {}
            Button("重新分析") { model.reanalyzeAll() }
        } message: {
            Text("几千张要几分钟。保留过的记录不会丢。")
        }
    }
}
