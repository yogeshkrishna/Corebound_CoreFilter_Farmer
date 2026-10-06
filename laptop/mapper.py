"""Translation-only reconstruction. All saved atlas coordinates are native pixels."""
from collections import OrderedDict
from pathlib import Path
import json
import math
import cv2
import numpy as np
from PIL import Image

TILE = 512


def looks_like_gameplay(image):
    """Require the pair of large movement pads, not white text from a menu/ad."""
    h, w = image.shape[:2]
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    boxes = []
    for threshold in (70, 110):
        binary = (gray > threshold).astype(np.uint8) * 255
        contours, _ = cv2.findContours(binary, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
        for contour in contours:
            x, y, bw, bh = cv2.boundingRect(contour)
            if x < w*.4 and y > h*.55 and y+bh < h*.97 and w*.075 < bw < w*.20 and h*.10 < bh < h*.33:
                boxes.append((x, y, bw, bh))
    for a in boxes:
        for b in boxes:
            if b[0] > a[0]+a[2]*.8 and b[0] < a[0]+a[2]*1.3 and abs(b[1]-a[1]) < h*.025 and abs(b[3]-a[3]) < h*.03:
                return True
    return False


def usable_pixels(image, metadata):
    h, w = image.shape[:2]
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    hsv = cv2.cvtColor(image, cv2.COLOR_BGR2HSV)
    mask = np.full((h, w), 255, np.uint8)
    # UI and moving, brightly coloured bots / Ember aura do not become terrain.
    # Exclude UI rectangles, not entire horizontal bands: roofs and floors at
    # the other side of the screen are still important native observations.
    mask[:int(h*.14), :int(w*.37)] = 0
    mask[:int(h*.17), int(w*.91):] = 0
    mask[int(h*.69):int(h*.95), int(w*.08):int(w*.38)] = 0
    dynamic = ((hsv[:, :, 1] > 65) & (hsv[:, :, 2] > 55)).astype(np.uint8)
    dynamic = cv2.dilate(dynamic, np.ones((19, 19), np.uint8))
    mask[dynamic != 0] = 0
    for rect in metadata.get('occlusions', []):
        x1, y1, x2, y2 = map(int, rect)
        mask[max(0, y1):min(h, y2), max(0, x1):min(w, x2)] = 0
    return gray, mask


class Atlas:
    def __init__(self, directory):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True)
        self.cache = OrderedDict()
        self.bounds = None
        self.tiles = set()
        self.dirty = set()
        self.revisions = {}
        self.saved_revisions = {}
        self.generation = 0

    def tile(self, tx, ty):
        key = (tx, ty)
        if key in self.cache:
            self.cache.move_to_end(key)
            return self.cache[key]
        path = self.directory / f'{tx}_{ty}.png'
        pixels = cv2.imread(str(path), cv2.IMREAD_UNCHANGED) if key in self.tiles and path.exists() else None
        if pixels is None:
            pixels = np.zeros((TILE, TILE, 4), np.uint8)
        score_path = self.directory / f'{tx}_{ty}.quality.png'
        scores = cv2.imread(str(score_path), cv2.IMREAD_GRAYSCALE) if key in self.tiles and score_path.exists() else None
        if scores is None:
            scores = np.zeros((TILE, TILE), np.uint8)
        self.cache[key] = (pixels, scores)
        while len(self.cache) > 40:
            self.flush_one(*self.cache.popitem(last=False))
        return pixels, scores

    def flush_one(self, key, data):
        if key not in self.dirty:
            return
        path = self.directory / f'{key[0]}_{key[1]}.png'
        temp = path.with_suffix('.tmp.png')
        pixels, scores = data
        if not cv2.imwrite(str(temp), pixels):
            raise OSError('Could not save native map tile')
        temp.replace(path)
        score_path = self.directory / f'{key[0]}_{key[1]}.quality.png'
        score_temp = score_path.with_suffix('.tmp.png')
        if not cv2.imwrite(str(score_temp), scores):
            raise OSError('Could not save tile observation quality')
        score_temp.replace(score_path)
        self.saved_revisions[key] = self.revisions[key]
        self.dirty.discard(key)

    def paint(self, image, mask, x, y):
        x, y = round(x), round(y)
        h, w = image.shape[:2]
        # Choose one actual source pixel using visibility, never brighten, average
        # or blend the game texture. Central unobscured observations win seams.
        padded_mask = cv2.copyMakeBorder(mask, 1, 1, 1, 1, cv2.BORDER_CONSTANT, value=0)
        distance = cv2.distanceTransform(padded_mask, cv2.DIST_L2, 3)[1:-1, 1:-1]
        quality = (np.minimum(distance, 64)+1).astype(np.uint8)
        for ty in range(y // TILE, (y + h - 1) // TILE + 1):
            for tx in range(x // TILE, (x + w - 1) // TILE + 1):
                ax, ay = max(x, tx * TILE), max(y, ty * TILE)
                bx, by = min(x + w, (tx + 1) * TILE), min(y + h, (ty + 1) * TILE)
                source = image[ay-y:by-y, ax-x:bx-x]
                valid = mask[ay-y:by-y, ax-x:bx-x] != 0
                if not valid.any():
                    continue
                tile, scores = self.tile(tx, ty)
                target = tile[ay-ty*TILE:by-ty*TILE, ax-tx*TILE:bx-tx*TILE]
                target_quality = scores[ay-ty*TILE:by-ty*TILE, ax-tx*TILE:bx-tx*TILE]
                source_quality = quality[ay-y:by-y, ax-x:bx-x]
                take = valid & ((target[:, :, 3] == 0) | (source_quality >= target_quality))
                target[take, :3] = source[take]
                target[take, 3] = 255
                target_quality[take] = source_quality[take]
                if take.any():
                    self.generation += 1
                    self.revisions[(tx, ty)] = self.generation
                    self.dirty.add((tx, ty))
                self.tiles.add((tx, ty))
                if self.bounds is None:
                    self.bounds = [ax, ay, bx, by]
                else:
                    self.bounds = [min(self.bounds[0], ax), min(self.bounds[1], ay),
                                   max(self.bounds[2], bx), max(self.bounds[3], by)]

    def flush(self):
        for key, pixels in self.cache.items():
            self.flush_one(key, pixels)

    def export(self, destination):
        self.flush()
        if self.bounds is None:
            raise ValueError('No mapped pixels yet')
        x, y, right, bottom = self.bounds
        w, h = right-x, bottom-y
        # Explicit limit prevents silent downsampling and uncontrolled allocation.
        if w*h > 120_000_000:
            raise ValueError('Map exceeds PNG memory limit. Native tiles remain available in the recording folder.')
        canvas = Image.new('RGBA', (w, h))
        for tx, ty in self.tiles:
            with Image.open(self.directory / f'{tx}_{ty}.png') as tile:
                canvas.paste(tile, (tx*TILE-x, ty*TILE-y))
        canvas.save(destination)
        return [w, h]


class Mapper:
    def __init__(self, directory):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True)
        self.sift = cv2.SIFT_create(nfeatures=2200, contrastThreshold=.025)
        self.anchors = []
        self.components = []
        self.last_size = None
        self.processed = self.aligned = self.rejected = 0
        self.last = None

    def features(self, image, metadata):
        gray, mask = usable_pixels(image, metadata)
        scale = min(1., 960 / image.shape[1])
        size = (round(image.shape[1]*scale), round(image.shape[0]*scale))
        small = cv2.resize(gray, size, interpolation=cv2.INTER_AREA)
        small_mask = cv2.resize(mask, size, interpolation=cv2.INTER_NEAREST)
        points, descriptors = self.sift.detectAndCompute(small, small_mask)
        return points, descriptors, scale, gray, mask

    def match(self, points, descriptors, anchor, width, height):
        if descriptors is None or anchor['descriptors'] is None or len(descriptors) < 14:
            return None
        pairs = anchor['matcher'].knnMatch(descriptors, k=2)
        good = [a for pair in pairs if len(pair) == 2 for a, b in [pair] if a.distance < .68*b.distance]
        if len(good) < 14:
            return None
        current = np.float32([points[m.queryIdx].pt for m in good])
        previous = np.float32([anchor['points'][m.trainIdx].pt for m in good])
        # Affine fit is a validity check only: we never apply its scale/rotation.
        affine, inliers = cv2.estimateAffinePartial2D(current, previous, method=cv2.RANSAC,
                                                     ransacReprojThreshold=2., maxIters=1000)
        if affine is None or inliers is None:
            return None
        if abs(math.hypot(affine[0, 0], affine[1, 0])-1) > .012 or abs(affine[1, 0]) > .012:
            return None
        shifts = previous-current
        chosen = shifts[inliers.ravel() != 0]
        delta = np.median(chosen, axis=0)
        consistent = np.linalg.norm(shifts-delta, axis=1) < 2.
        n = int(consistent.sum())
        if n < 14 or n/len(good) < .65:
            return None
        spread = np.ptp(current[consistent], axis=0)
        if spread[0] < width*.13 or spread[1] < height*.08:
            return None
        return delta, n, n/len(good)

    def ingest(self, image, metadata, frame_id):
        if not looks_like_gameplay(image):
            self.processed += 1
            self.last = dict(frame=frame_id, mapped=False, reason='Movement pads absent: kept as original evidence')
            return self.last
        points, descriptors, scale, gray, mask = self.features(image, metadata)
        h, w = image.shape[:2]
        self.processed += 1
        size = (w, h)
        candidates = []
        # Recent anchors plus spaced older views permit reversals and loop closure.
        relevant = [a for a in self.anchors if a['size'] == size]
        recent = relevant[-6:]
        older = relevant[:-6]
        references = recent + older[::max(1, len(older)//16)][-16:]
        for anchor in references:
            result = self.match(points, descriptors, anchor, w*scale, h*scale)
            if result:
                delta, count, confidence = result
                origin = np.array(anchor['origin']) + delta/scale
                candidates.append((count, confidence, origin, anchor['component']))
        candidates.sort(key=lambda c: c[0], reverse=True)
        ambiguity = False
        if candidates:
            winner = candidates[0]
            for other in candidates[1:]:
                if other[0] > winner[0]*.8 and (other[3] != winner[3] or np.linalg.norm(other[2]-winner[2]) > 10):
                    ambiguity = True
                    break
        if candidates and not ambiguity:
            count, confidence, origin, component = candidates[0]
            self.aligned += 1
            linked = True
        elif descriptors is not None and len(points) >= 20:
            # Lost tracking is explicit. Never paste a view at a guessed position.
            component = len(self.components)
            origin = np.array([0., 0.])
            count, confidence, linked = 0, 0., False
            self.components.append({'atlas': Atlas(self.directory / f'component-{component}'),
                                    'borders': {}, 'frames': 0})
            self.rejected += bool(self.anchors)
        else:
            self.last = dict(frame=frame_id, mapped=False, reason='Insufficient static detail / game menu', points=len(points))
            return self.last
        group = self.components[component]
        group['atlas'].paint(image, mask, *origin)
        group['frames'] += 1
        # Long axis-aligned edges are observations, not invented collision geometry.
        edges = cv2.Canny(gray, 45, 110)
        edges[mask == 0] = 0
        lines = cv2.HoughLinesP(edges, 1, np.pi/180, 65, minLineLength=max(36, w//30), maxLineGap=8)
        if lines is not None:
            for x1, y1, x2, y2 in lines.reshape(-1, 4):
                if abs(x2-x1) > 4 and abs(y2-y1) > 4:
                    continue
                a = [int(round(x1+origin[0])), int(round(y1+origin[1])),
                     int(round(x2+origin[0])), int(round(y2+origin[1]))]
                key = tuple(round(v/8) for v in a)
                group['borders'][key] = a
        anchor = dict(points=points, descriptors=descriptors, origin=origin.tolist(), size=size, component=component)
        # Keep stationary repeats out of the anchor bank.
        if not relevant or component != relevant[-1]['component'] or np.linalg.norm(origin-np.array(relevant[-1]['origin'])) > 45:
            matcher = cv2.FlannBasedMatcher(dict(algorithm=1, trees=4), dict(checks=64))
            matcher.add([descriptors])
            matcher.train()
            anchor['matcher'] = matcher
            self.anchors.append(anchor)
            if len(self.anchors) > 160:
                # Thin old views without discarding every older corridor anchor.
                self.anchors = self.anchors[:1]+self.anchors[2:81:2]+self.anchors[81:]
        self.last = dict(frame=frame_id, mapped=True, linked=linked, component=component,
                         origin=origin.tolist(), matches=count, confidence=confidence, points=len(points))
        return self.last

    def summary(self):
        return dict(processed=self.processed, aligned=self.aligned, disconnected=self.rejected,
                    last=self.last, components=[dict(id=i, frames=g['frames'], bounds=g['atlas'].bounds,
                    tiles=[[k[0], k[1], g['atlas'].saved_revisions.get(k, 0)] for k in sorted(g['atlas'].tiles)], borders=len(g['borders']))
                    for i, g in enumerate(self.components)])

    def flush(self):
        for component, group in enumerate(self.components):
            group['atlas'].flush()
            path = self.directory / f'component-{component}' / 'borders.json'
            temporary = path.with_suffix('.tmp')
            temporary.write_text(json.dumps(list(group['borders'].values())))
            temporary.replace(path)
        path = self.directory / 'map.json'
        temporary = path.with_suffix('.tmp')
        temporary.write_text(json.dumps(self.summary(), indent=2))
        temporary.replace(path)
