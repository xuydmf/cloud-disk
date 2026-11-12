import Foundation
import Capacitor
import Photos
import UniformTypeIdentifiers

/**
 * 自定义相册（按日期分组、多选、选完立即关）。
 *
 * 为什么不用 @capacitor/camera：
 *   · pickImages 是系统 PHPicker，界面不是我们要的；
 *   · chooseFromGallery 是 IONCAMR 自绘相册，关窗前要等整批导出。
 *
 * 这里自己用 UICollectionView + PHAsset 画相册：
 *   · 点「添加」先 dismiss，再在后台逐张写临时文件，最后才 resolve；
 *   · 照片给 webPath（JS 读字节后压缩），视频给 file:// 路径（FileTransfer 直传）。
 */
@objc(PhotoAlbumPlugin)
public class PhotoAlbumPlugin: CAPPlugin, CAPBridgedPlugin {

    public let identifier = "PhotoAlbumPlugin"
    public let jsName = "PhotoAlbum"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "pick", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "checkPermissions", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "requestPermissions", returnType: CAPPluginReturnPromise)
    ]

    private var call: CAPPluginCall?
    private var album: PhotoAlbumViewController?

    @objc func checkPermissions(_ call: CAPPluginCall) {
        call.resolve(["photos": permissionState()])
    }

    @objc func requestPermissions(_ call: CAPPluginCall) {
        PHPhotoLibrary.requestAuthorization(for: .readWrite) { _ in
            DispatchQueue.main.async { call.resolve(["photos": self.permissionState()]) }
        }
    }

    private func permissionState() -> String {
        switch PHPhotoLibrary.authorizationStatus(for: .readWrite) {
        case .authorized: return "granted"
        case .limited: return "limited"
        case .denied, .restricted: return "denied"
        case .notDetermined: return "prompt"
        @unknown default: return "prompt"
        }
    }

    @objc func pick(_ call: CAPPluginCall) {
        guard self.call == nil else {
            call.reject("相册选择尚未结束")
            return
        }
        let limit = max(1, call.getInt("limit") ?? 20)
        self.call = call

        switch PHPhotoLibrary.authorizationStatus(for: .readWrite) {
        case .authorized, .limited:
            present(limit: limit, call: call)
        case .notDetermined:
            PHPhotoLibrary.requestAuthorization(for: .readWrite) { [weak self] result in
                DispatchQueue.main.async {
                    if result == .authorized || result == .limited {
                        self?.present(limit: limit, call: call)
                    } else {
                        self?.call = nil
                        call.reject("没有相册访问权限")
                    }
                }
            }
        default:
            self.call = nil
            call.reject("没有相册访问权限")
        }
    }

    private func present(limit: Int, call: CAPPluginCall) {
        guard let bridge = self.bridge, let host = bridge.viewController else {
            self.call = nil
            call.reject("无法打开相册")
            return
        }
        let album = PhotoAlbumViewController(limit: limit)
        album.onCancel = { [weak self] in
            self?.call = nil
            self?.album = nil
            call.reject("User cancelled photos app")
        }
        album.onDone = { [weak self] assets in
            self?.export(assets: assets, call: call)
        }
        self.album = album
        host.present(album, animated: true, completion: nil)
    }

    private func export(assets: [PHAsset], call: CAPPluginCall) {
        self.album = nil
        guard !assets.isEmpty else {
            self.call = nil
            call.reject("没有选择照片")
            return
        }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("photo-album", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)

        DispatchQueue.global(qos: .userInitiated).async {
            var files: [[String: Any]] = []
            for asset in assets {
                if let file = self.write(asset: asset, to: dir) {
                    files.append(file)
                }
            }
            DispatchQueue.main.async {
                self.call = nil
                guard !files.isEmpty else {
                    call.reject("导出失败，请重试")
                    return
                }
                // 补上 webPath：JS 里 <img>/fetch 不能直接读 file://
                var output: [[String: Any]] = []
                for var file in files {
                    if let path = file["path"] as? String,
                       let url = URL(string: path),
                       let web = self.bridge?.portablePath(fromLocalURL: url) {
                        file["webPath"] = web.absoluteString
                    }
                    output.append(file)
                }
                call.resolve(["files": output])
            }
        }
    }

    /// 把 PHAsset 的原文件写到临时目录。照片/视频都走 PHAssetResourceManager，
    /// 不经过 UIImage，避免整批解码占内存；视频也不会进 JS。
    private func write(asset: PHAsset, to dir: URL) -> [String: Any]? {
        let resources = PHAssetResource.assetResources(for: asset)
        let preferred: [PHAssetResourceType] = asset.mediaType == .video
            ? [.fullSizeVideo, .video]
            : [.fullSizePhoto, .photo]
        guard let resource = resources.first(where: { preferred.contains($0.type) }) ?? resources.first else {
            return nil
        }

        let originalName = resource.originalFilename
        let ext = (originalName as NSString).pathExtension.isEmpty
            ? (asset.mediaType == .video ? "mov" : "jpg")
            : (originalName as NSString).pathExtension
        let date = asset.creationDate ?? Date()
        let prefix = asset.mediaType == .video ? "VID" : "IMG"
        let base = "\(prefix)_\(Self.stampFormatter.string(from: date))_\(UUID().uuidString.prefix(6))"
        let dest = dir.appendingPathComponent("\(base).\(ext)")

        let options = PHAssetResourceRequestOptions()
        options.isNetworkAccessAllowed = true

        let semaphore = DispatchSemaphore(value: 0)
        var writeError: Error?
        PHAssetResourceManager.default().writeData(for: resource, toFile: dest, options: options) { error in
            writeError = error
            semaphore.signal()
        }
        semaphore.wait()
        if writeError != nil {
            return nil
        }

        let attrs = try? FileManager.default.attributesOfItem(atPath: dest.path)
        let size = (attrs?[.size] as? NSNumber)?.int64Value ?? 0
        let mime = UTType(resource.uniformTypeIdentifier)?.preferredMIMEType
            ?? (asset.mediaType == .video ? "video/quicktime" : "image/jpeg")
        let iso = ISO8601DateFormatter().string(from: date)

        return [
            "path": dest.absoluteString,
            "name": "\(base).\(ext)",
            "size": size,
            "type": mime,
            "creationDate": iso,
            "isVideo": asset.mediaType == .video
        ]
    }

    private static let stampFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyyMMdd_HHmmss"
        return f
    }()
}
