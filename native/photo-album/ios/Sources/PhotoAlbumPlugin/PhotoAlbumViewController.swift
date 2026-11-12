import UIKit
import Photos

/// 自定义相册：按日期分组、三列缩略图、多选、LIVE/视频角标。
final class PhotoAlbumViewController: UIViewController {

    var onDone: (([PHAsset]) -> Void)?
    var onCancel: (() -> Void)?

    private let limit: Int
    private let imageManager = PHCachingImageManager()
    private var sections: [(day: Date, title: String, assets: [PHAsset])] = []
    private var selected: [PHAsset] = []
    private var selectedIDs = Set<String>()
    private var thumbnailSize = CGSize.zero
    private var finishing = false

    private let layout: UICollectionViewFlowLayout
    private let collectionView: UICollectionView
    private let header = UIView()
    private let titleLabel = UILabel()
    private let closeButton = UIButton(type: .system)
    private let bottomBar = UIView()
    private let countLabel = UILabel()
    private let doneButton = UIButton(type: .system)
    private let spinner = UIActivityIndicatorView(style: .medium)
    private let emptyLabel = UILabel()

    init(limit: Int) {
        self.limit = max(1, limit)
        let flow = UICollectionViewFlowLayout()
        self.layout = flow
        self.collectionView = UICollectionView(frame: .zero, collectionViewLayout: flow)
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .fullScreen
        overrideUserInterfaceStyle = .light
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override var preferredStatusBarStyle: UIStatusBarStyle { .darkContent }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        setupUI()
        loadAssets()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let width = view.bounds.width
        guard width > 0 else { return }
        let itemWidth = (width - 8) / 3
        if abs(layout.itemSize.width - itemWidth) > 0.5 {
            layout.itemSize = CGSize(width: itemWidth, height: itemWidth)
            layout.headerReferenceSize = CGSize(width: width, height: 36)
            layout.invalidateLayout()
            thumbnailSize = CGSize(width: itemWidth * UIScreen.main.scale,
                                   height: itemWidth * UIScreen.main.scale)
            collectionView.reloadData()
        }
    }

    // MARK: - UI

    private func setupUI() {
        header.translatesAutoresizingMaskIntoConstraints = false
        header.backgroundColor = .systemBackground
        view.addSubview(header)

        titleLabel.text = "本地相册"
        titleLabel.font = .systemFont(ofSize: 17, weight: .semibold)
        titleLabel.textColor = .label
        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        header.addSubview(titleLabel)

        let closeImage = UIImage(systemName: "xmark", withConfiguration: UIImage.SymbolConfiguration(pointSize: 15, weight: .semibold))
        closeButton.setImage(closeImage, for: .normal)
        closeButton.tintColor = .label
        closeButton.addTarget(self, action: #selector(closeTapped), for: .touchUpInside)
        closeButton.translatesAutoresizingMaskIntoConstraints = false
        header.addSubview(closeButton)

        let hairline = UIView()
        hairline.backgroundColor = .separator
        hairline.translatesAutoresizingMaskIntoConstraints = false
        header.addSubview(hairline)

        layout.minimumLineSpacing = 2
        layout.minimumInteritemSpacing = 2
        layout.sectionInset = UIEdgeInsets(top: 2, left: 2, bottom: 2, right: 2)
        layout.headerReferenceSize = CGSize(width: view.bounds.width, height: 36)

        collectionView.backgroundColor = .systemBackground
        collectionView.contentInsetAdjustmentBehavior = .never
        collectionView.dataSource = self
        collectionView.delegate = self
        collectionView.alwaysBounceVertical = true
        collectionView.register(PhotoAlbumCell.self, forCellWithReuseIdentifier: PhotoAlbumCell.reuseID)
        collectionView.register(PhotoAlbumHeader.self,
                                forSupplementaryViewOfKind: UICollectionView.elementKindSectionHeader,
                                withReuseIdentifier: PhotoAlbumHeader.reuseID)
        collectionView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(collectionView)

        bottomBar.translatesAutoresizingMaskIntoConstraints = false
        bottomBar.backgroundColor = .systemBackground
        view.addSubview(bottomBar)

        let barLine = UIView()
        barLine.backgroundColor = .separator
        barLine.translatesAutoresizingMaskIntoConstraints = false
        bottomBar.addSubview(barLine)

        countLabel.text = "已选 0/\(limit)"
        countLabel.font = .systemFont(ofSize: 13)
        countLabel.textColor = .secondaryLabel
        countLabel.translatesAutoresizingMaskIntoConstraints = false
        bottomBar.addSubview(countLabel)

        doneButton.setTitle("添加", for: .normal)
        doneButton.titleLabel?.font = .systemFont(ofSize: 15, weight: .semibold)
        doneButton.setTitleColor(.white, for: .normal)
        doneButton.setTitleColor(UIColor.white.withAlphaComponent(0.55), for: .disabled)
        doneButton.backgroundColor = .systemBlue
        doneButton.layer.cornerRadius = 8
        doneButton.contentEdgeInsets = UIEdgeInsets(top: 0, left: 18, bottom: 0, right: 18)
        doneButton.addTarget(self, action: #selector(doneTapped), for: .touchUpInside)
        doneButton.isEnabled = false
        doneButton.translatesAutoresizingMaskIntoConstraints = false
        bottomBar.addSubview(doneButton)

        spinner.hidesWhenStopped = true
        spinner.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(spinner)

        emptyLabel.text = "相册里还没有照片"
        emptyLabel.font = .systemFont(ofSize: 14)
        emptyLabel.textColor = .secondaryLabel
        emptyLabel.isHidden = true
        emptyLabel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(emptyLabel)

        NSLayoutConstraint.activate([
            header.topAnchor.constraint(equalTo: view.topAnchor),
            header.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            header.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            header.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 52),

            titleLabel.centerXAnchor.constraint(equalTo: header.centerXAnchor),
            titleLabel.centerYAnchor.constraint(equalTo: header.bottomAnchor, constant: -26),

            closeButton.trailingAnchor.constraint(equalTo: header.trailingAnchor, constant: -18),
            closeButton.centerYAnchor.constraint(equalTo: titleLabel.centerYAnchor),
            closeButton.widthAnchor.constraint(equalToConstant: 32),
            closeButton.heightAnchor.constraint(equalToConstant: 32),

            hairline.leadingAnchor.constraint(equalTo: header.leadingAnchor),
            hairline.trailingAnchor.constraint(equalTo: header.trailingAnchor),
            hairline.bottomAnchor.constraint(equalTo: header.bottomAnchor),
            hairline.heightAnchor.constraint(equalToConstant: 0.5),

            collectionView.topAnchor.constraint(equalTo: header.bottomAnchor),
            collectionView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            collectionView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            collectionView.bottomAnchor.constraint(equalTo: bottomBar.topAnchor),

            bottomBar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            bottomBar.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            bottomBar.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            bottomBar.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -56),

            barLine.leadingAnchor.constraint(equalTo: bottomBar.leadingAnchor),
            barLine.trailingAnchor.constraint(equalTo: bottomBar.trailingAnchor),
            barLine.topAnchor.constraint(equalTo: bottomBar.topAnchor),
            barLine.heightAnchor.constraint(equalToConstant: 0.5),

            countLabel.leadingAnchor.constraint(equalTo: bottomBar.leadingAnchor, constant: 18),
            countLabel.centerYAnchor.constraint(equalTo: bottomBar.topAnchor, constant: 28),

            doneButton.trailingAnchor.constraint(equalTo: bottomBar.trailingAnchor, constant: -18),
            doneButton.centerYAnchor.constraint(equalTo: countLabel.centerYAnchor),
            doneButton.heightAnchor.constraint(equalToConstant: 36),

            spinner.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            spinner.centerYAnchor.constraint(equalTo: collectionView.centerYAnchor),

            emptyLabel.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            emptyLabel.centerYAnchor.constraint(equalTo: collectionView.centerYAnchor)
        ])
    }

    // MARK: - Data

    private func loadAssets() {
        spinner.startAnimating()
        let options = PHFetchOptions()
        options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        options.predicate = NSPredicate(format: "mediaType == %d OR mediaType == %d",
                                        PHAssetMediaType.image.rawValue,
                                        PHAssetMediaType.video.rawValue)

        let result = PHAsset.fetchAssets(with: options)
        var buckets: [Date: [PHAsset]] = [:]
        result.enumerateObjects { asset, _, _ in
            let day = Calendar.current.startOfDay(for: asset.creationDate ?? Date())
            buckets[day, default: []].append(asset)
        }
        sections = buckets.keys.sorted(by: >).map { day in
            (day, Self.dayFormatter.string(from: day), buckets[day] ?? [])
        }
        spinner.stopAnimating()
        emptyLabel.isHidden = !sections.isEmpty
        collectionView.reloadData()
    }

    private func updateBar(flash: Bool = false) {
        countLabel.text = "已选 \(selected.count)/\(limit)"
        doneButton.isEnabled = !selected.isEmpty
        doneButton.setTitle(selected.isEmpty ? "添加" : "添加 \(selected.count)", for: .normal)
        if flash {
            countLabel.textColor = .systemRed
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { [weak self] in
                self?.countLabel.textColor = .secondaryLabel
            }
        }
    }

    @objc private func closeTapped() {
        guard !finishing else { return }
        finishing = true
        dismiss(animated: true) { [weak self] in
            self?.onCancel?()
        }
    }

    @objc private func doneTapped() {
        guard !finishing, !selected.isEmpty else { return }
        finishing = true
        let assets = selected
        // 关键：先关窗，再在插件里后台导出
        dismiss(animated: true) { [weak self] in
            self?.onDone?(assets)
        }
    }

    private static let dayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "zh_CN")
        f.dateFormat = "M月d日 EEEE"
        return f
    }()
}

// MARK: - UICollectionView

extension PhotoAlbumViewController: UICollectionViewDataSource, UICollectionViewDelegate {

    func numberOfSections(in collectionView: UICollectionView) -> Int {
        sections.count
    }

    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        sections[section].assets.count
    }

    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: PhotoAlbumCell.reuseID, for: indexPath) as! PhotoAlbumCell
        let asset = sections[indexPath.section].assets[indexPath.item]
        cell.configure(asset: asset,
                       selected: selectedIDs.contains(asset.localIdentifier),
                       imageManager: imageManager,
                       targetSize: thumbnailSize)
        return cell
    }

    func collectionView(_ collectionView: UICollectionView, viewForSupplementaryElementOfKind kind: String, at indexPath: IndexPath) -> UICollectionReusableView {
        let header = collectionView.dequeueReusableSupplementaryView(ofKind: kind,
                                                                     withReuseIdentifier: PhotoAlbumHeader.reuseID,
                                                                     for: indexPath) as! PhotoAlbumHeader
        header.title = sections[indexPath.section].title
        return header
    }

    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        collectionView.deselectItem(at: indexPath, animated: false)
        let asset = sections[indexPath.section].assets[indexPath.item]
        if let index = selected.firstIndex(where: { $0.localIdentifier == asset.localIdentifier }) {
            selected.remove(at: index)
            selectedIDs.remove(asset.localIdentifier)
        } else {
            guard selected.count < limit else {
                UIImpactFeedbackGenerator(style: .rigid).impactOccurred()
                updateBar(flash: true)
                return
            }
            selected.append(asset)
            selectedIDs.insert(asset.localIdentifier)
        }
        if let cell = collectionView.cellForItem(at: indexPath) as? PhotoAlbumCell {
            cell.setSelected(selectedIDs.contains(asset.localIdentifier))
        }
        updateBar()    }
}

// MARK: - Cell

final class PhotoAlbumCell: UICollectionViewCell {

    static let reuseID = "PhotoAlbumCell"

    private let imageView = UIImageView()
    private let badgeContainer = UIView()
    private let badgeLabel = UILabel()
    private let checkCircle = UIView()
    private let checkImage = UIImageView()

    private(set) var assetID = ""
    private weak var manager: PHCachingImageManager?
    private var requestID: PHImageRequestID = PHInvalidImageRequestID

    override init(frame: CGRect) {
        super.init(frame: frame)
        contentView.clipsToBounds = true
        contentView.backgroundColor = .secondarySystemBackground

        imageView.contentMode = .scaleAspectFill
        imageView.clipsToBounds = true
        imageView.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(imageView)

        badgeContainer.backgroundColor = UIColor.black.withAlphaComponent(0.55)
        badgeContainer.layer.cornerRadius = 3
        badgeContainer.isHidden = true
        badgeContainer.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(badgeContainer)

        badgeLabel.font = .systemFont(ofSize: 9, weight: .bold)
        badgeLabel.textColor = .white
        badgeLabel.translatesAutoresizingMaskIntoConstraints = false
        badgeContainer.addSubview(badgeLabel)

        checkCircle.layer.cornerRadius = 11
        checkCircle.layer.borderWidth = 1.5
        checkCircle.layer.borderColor = UIColor.white.cgColor
        checkCircle.layer.shadowColor = UIColor.black.cgColor
        checkCircle.layer.shadowOpacity = 0.25
        checkCircle.layer.shadowRadius = 2
        checkCircle.layer.shadowOffset = CGSize(width: 0, height: 1)
        checkCircle.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(checkCircle)

        checkImage.image = UIImage(systemName: "checkmark",
                                   withConfiguration: UIImage.SymbolConfiguration(pointSize: 11, weight: .bold))
        checkImage.tintColor = .white
        checkImage.isHidden = true
        checkImage.translatesAutoresizingMaskIntoConstraints = false
        checkCircle.addSubview(checkImage)

        NSLayoutConstraint.activate([
            imageView.topAnchor.constraint(equalTo: contentView.topAnchor),
            imageView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
            imageView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
            imageView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),

            badgeContainer.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 5),
            badgeContainer.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 5),
            badgeContainer.heightAnchor.constraint(equalToConstant: 16),

            badgeLabel.leadingAnchor.constraint(equalTo: badgeContainer.leadingAnchor, constant: 5),
            badgeLabel.trailingAnchor.constraint(equalTo: badgeContainer.trailingAnchor, constant: -5),
            badgeLabel.centerYAnchor.constraint(equalTo: badgeContainer.centerYAnchor),

            checkCircle.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -5),
            checkCircle.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 5),
            checkCircle.widthAnchor.constraint(equalToConstant: 22),
            checkCircle.heightAnchor.constraint(equalToConstant: 22),

            checkImage.centerXAnchor.constraint(equalTo: checkCircle.centerXAnchor),
            checkImage.centerYAnchor.constraint(equalTo: checkCircle.centerYAnchor)
        ])
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func prepareForReuse() {
        super.prepareForReuse()
        if requestID != PHInvalidImageRequestID {
            manager?.cancelImageRequest(requestID)
        }
        requestID = PHInvalidImageRequestID
        imageView.image = nil
        assetID = ""
        badgeContainer.isHidden = true
    }

    /// 只更新勾选态，不重新请求缩略图（避免点一下闪一下）
    func setSelected(_ selected: Bool) {
        checkCircle.backgroundColor = selected ? .systemBlue : UIColor.black.withAlphaComponent(0.28)
        checkImage.isHidden = !selected
        checkCircle.layer.borderColor = (selected ? UIColor.white : UIColor.white.withAlphaComponent(0.9)).cgColor
    }

    func configure(asset: PHAsset, selected: Bool, imageManager: PHCachingImageManager, targetSize: CGSize) {
        assetID = asset.localIdentifier
        manager = imageManager

        if asset.mediaType == .video {
            let total = Int(asset.duration.rounded())
            badgeLabel.text = String(format: "%d:%02d", total / 60, total % 60)
        } else if asset.mediaSubtypes.contains(.photoLive) {
            badgeLabel.text = "LIVE"
        } else {
            badgeLabel.text = nil
        }
        badgeContainer.isHidden = (badgeLabel.text ?? "").isEmpty
        setSelected(selected)

        if requestID != PHInvalidImageRequestID {
            imageManager.cancelImageRequest(requestID)
        }
        guard targetSize.width > 0 else { return }
        let options = PHImageRequestOptions()
        options.deliveryMode = .opportunistic
        options.resizeMode = .fast
        options.isNetworkAccessAllowed = true
        let id = asset.localIdentifier
        requestID = imageManager.requestImage(for: asset,
                                              targetSize: targetSize,
                                              contentMode: .aspectFill,
                                              options: options) { [weak self] image, _ in
            guard let self, self.assetID == id, let image else { return }
            self.imageView.image = image
        }
    }
}

// MARK: - Section header

final class PhotoAlbumHeader: UICollectionReusableView {

    static let reuseID = "PhotoAlbumHeader"

    private let label = UILabel()

    var title: String = "" {
        didSet { label.text = title }
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .systemBackground
        label.font = .systemFont(ofSize: 13, weight: .semibold)
        label.textColor = .secondaryLabel
        label.translatesAutoresizingMaskIntoConstraints = false
        addSubview(label)
        NSLayoutConstraint.activate([
            label.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 12),
            label.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -6)
        ])
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
}
