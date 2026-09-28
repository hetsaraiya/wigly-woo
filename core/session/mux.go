package session

import "sync"

// videoQueueCap is how many droppable video frames may wait (about 100 ms
// at 60 fps). Past that the mux discards until the next keyframe instead of
// growing latency. Smaller caps turned ordinary Wi-Fi hiccups into drops.
const videoQueueCap = 6

// audioQueueCap bounds the jitter the writer will introduce. Older packets
// are dropped so a stall cannot pile up more than a few dozen milliseconds
// of Opus/AAC at typical packet rates.
const audioQueueCap = 8

// Mux orders outbound frames: control and meta, then audio, then video.
// Video marked droppable is discarded when the queue is past videoQueueCap,
// and stays discarded until a keyframe arrives.
type Mux struct {
	mu        sync.Mutex
	cond      *sync.Cond
	urgent    []Frame // control + meta
	audio     []Frame
	video     []Frame
	dropVideo bool
	closed    bool

	// OnDrop runs (outside the lock) for every discarded video frame, so the
	// sender keeps asking its encoder for a keyframe until one arrives. The
	// encoder only emits frames when the screen changes, so its own keyframe
	// interval can be minutes away. Callers throttle.
	OnDrop func()
}

// NewMux returns an empty mux.
func NewMux() *Mux {
	m := &Mux{}
	m.cond = sync.NewCond(&m.mu)
	return m
}

// Enqueue adds a frame. A closed mux drops it.
func (m *Mux) Enqueue(f Frame) {
	m.mu.Lock()
	m.enqueueLocked(f)
	dropping := m.dropVideo
	onDrop := m.OnDrop
	m.mu.Unlock()
	if dropping && onDrop != nil {
		onDrop()
	}
}

func (m *Mux) enqueueLocked(f Frame) {
	if m.closed {
		return
	}
	switch f.Channel {
	case ChanControl, ChanMeta:
		m.urgent = append(m.urgent, f)
	case ChanAudio:
		if len(m.audio) >= audioQueueCap {
			m.audio = dropOldest(m.audio)
		}
		m.audio = append(m.audio, f)
	case ChanVideo:
		m.enqueueVideoLocked(f)
	default:
		// Unknown channels share the urgent lane so a future channel is not
		// stuck behind video.
		m.urgent = append(m.urgent, f)
	}
	m.cond.Signal()
}

func (m *Mux) enqueueVideoLocked(f Frame) {
	key := f.Flags&FlagKeyframe != 0
	droppable := f.Flags&FlagDroppable != 0
	if m.dropVideo {
		if !key {
			return
		}
		m.video = pinned(m.video)
		m.dropVideo = false
	}
	if droppable && !key && len(m.video) >= videoQueueCap {
		m.video = pinned(m.video)
		m.dropVideo = true
		return
	}
	m.video = append(m.video, f)
}

// pinned keeps only frames that must not be dropped, such as codec config:
// without it the decoder can never start again.
func pinned(q []Frame) []Frame {
	var keep []Frame
	for _, f := range q {
		if f.Flags&FlagDroppable == 0 {
			keep = append(keep, f)
		}
	}
	return keep
}

// dropOldest removes the oldest droppable frame, or the oldest frame when
// every queued frame is pinned.
func dropOldest(q []Frame) []Frame {
	for i, f := range q {
		if f.Flags&FlagDroppable != 0 {
			return append(q[:i:i], q[i+1:]...)
		}
	}
	return q[1:]
}

// Next returns the next frame to write. ok is false when the mux is closed
// and empty.
func (m *Mux) Next() (Frame, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	for !m.closed && len(m.urgent) == 0 && len(m.audio) == 0 && len(m.video) == 0 {
		m.cond.Wait()
	}
	if f, ok := pop(&m.urgent); ok {
		return f, true
	}
	if f, ok := pop(&m.audio); ok {
		return f, true
	}
	if f, ok := pop(&m.video); ok {
		return f, true
	}
	return Frame{}, false
}

func pop(q *[]Frame) (Frame, bool) {
	if len(*q) == 0 {
		return Frame{}, false
	}
	f := (*q)[0]
	*q = (*q)[1:]
	return f, true
}

// Close unblocks Next. Frames already queued are still returned.
func (m *Mux) Close() {
	m.mu.Lock()
	m.closed = true
	m.mu.Unlock()
	m.cond.Broadcast()
}

// Dropping reports whether video is currently being discarded until a keyframe.
func (m *Mux) Dropping() bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.dropVideo
}
