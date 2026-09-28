package session

import "sync"

// videoQueueCap is how many droppable video frames may wait. Past that the
// mux discards until the next keyframe instead of growing latency.
const videoQueueCap = 3

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
	defer m.mu.Unlock()
	if m.closed {
		return
	}
	switch f.Channel {
	case ChanControl, ChanMeta:
		m.urgent = append(m.urgent, f)
	case ChanAudio:
		if len(m.audio) >= audioQueueCap {
			m.audio = m.audio[1:]
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
		m.video = nil
		m.dropVideo = false
	}
	if droppable && !key && len(m.video) >= videoQueueCap {
		m.video = nil
		m.dropVideo = true
		return
	}
	m.video = append(m.video, f)
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
