import { defineConfig, minimalPreset } from '@vite-pwa/assets-generator/config'

// 品牌底色：maskable 与 Apple 图标的安全区留白必须用它填满，
// 否则启动器把图标裁成圆形时会露出一圈白边。
const brand = '#7C9BFF'

export default defineConfig({
  preset: {
    ...minimalPreset,
    maskable: { ...minimalPreset.maskable, resizeOptions: { ...minimalPreset.maskable.resizeOptions, background: brand } },
    apple: { ...minimalPreset.apple, resizeOptions: { ...minimalPreset.apple.resizeOptions, background: brand } }
  },
  images: ['public/logo.svg']
})
