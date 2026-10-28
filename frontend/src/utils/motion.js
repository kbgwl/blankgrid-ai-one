/* 系统级「减少动态效果」偏好:关掉施工动效,内容照常可读 */
export function motionOk() {
  return !(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches)
}

/* 数字补间:进度环、tokens、评分用同一套缓动 */
export function tween(from, to, ms, cb) {
  if (!motionOk() || from === to) { cb(to); return () => {} }
  let raf = 0
  const t0 = performance.now()
  const tick = (now) => {
    const k = Math.min(1, (now - t0) / ms)
    cb(Math.round(from + (to - from) * (1 - Math.pow(1 - k, 3))))
    if (k < 1) raf = requestAnimationFrame(tick)
  }
  raf = requestAnimationFrame(tick)
  return () => cancelAnimationFrame(raf)
}
