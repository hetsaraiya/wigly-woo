cask "wigly-woo" do
  arch arm: "arm64", intel: "x86_64"

  version "__VERSION__"
  sha256 arm:   "__ARM64_SHA256__",
         intel: "__X86_64_SHA256__"

  url "https://github.com/hetsaraiya/wigly-woo/releases/download/v#{version}/WiglyWoo-v#{version}-macos-#{arch}.zip"
  name "Wigly Woo"
  desc "Nearby file transfer and remote companion"
  homepage "https://github.com/hetsaraiya/wigly-woo"

  depends_on macos: :ventura

  app "WiglyWoo.app"

  caveats <<~EOS
    Wigly Woo is ad-hoc signed and is not notarized by Apple.
    On first launch, macOS may require approval in:
      System Settings > Privacy & Security > Open Anyway
  EOS
end
