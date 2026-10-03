import { useRef, useState, type DragEvent } from 'react'

/**
 * 파일을 끌어다 놓아 올리기. 안쪽 요소를 지날 때마다 dragenter·dragleave 가 쌓이므로 깊이를 세어 표시를 끕니다.
 */
export function useFileDrop({ canDrop, onFiles, onRefused }: {
  canDrop: boolean
  onFiles: (files: File[]) => void
  onRefused: () => void
}) {
  const [dragging, setDragging] = useState(false)
  const depth = useRef(0)
  return {
    dragging,
    reset: () => {
      depth.current = 0
      setDragging(false)
    },
    handlers: {
      onDragEnter: (e: DragEvent) => {
        if (!e.dataTransfer.types.includes('Files')) return
        depth.current++
        setDragging(true)
      },
      onDragLeave: () => {
        depth.current = Math.max(0, depth.current - 1)
        if (depth.current === 0) setDragging(false)
      },
      onDragOver: (e: DragEvent) => e.preventDefault(),
      onDrop: (e: DragEvent) => {
        e.preventDefault()
        depth.current = 0
        setDragging(false)
        if (!canDrop) return onRefused()
        const files = Array.from(e.dataTransfer.files)
        if (files.length) onFiles(files)
      },
    },
  }
}
