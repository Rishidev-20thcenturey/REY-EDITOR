use crate::Layer;

#[derive(Clone, Copy, Debug, Default, serde::Serialize, serde::Deserialize, PartialEq)]
pub enum BlendMode { #[default] Normal, Multiply, Screen, Overlay }

pub fn over(dst: [u8; 4], src: [u8; 4], opacity: u8, mask: u8) -> [u8; 4] {
    over_mode(dst, src, opacity, mask, BlendMode::Normal)
}

pub fn over_mode(dst: [u8; 4], src: [u8; 4], opacity: u8, mask: u8, mode: BlendMode) -> [u8; 4] {
    let sa = u32::from(src[3]) * u32::from(opacity) * u32::from(mask) / (255 * 255);
    let da = u32::from(dst[3]);
    let out_a = sa + da * (255 - sa) / 255;
    if out_a == 0 { return [0; 4]; }
    let mut out = [0; 4];
    for channel in 0..3 {
        let s = u32::from(src[channel]); let d = u32::from(dst[channel]);
        let blended = match mode { BlendMode::Normal => s, BlendMode::Multiply => s * d / 255, BlendMode::Screen => 255 - (255 - s) * (255 - d) / 255, BlendMode::Overlay => if d < 128 { 2 * s * d / 255 } else { 255 - 2 * (255 - s) * (255 - d) / 255 } };
        let value = blended * sa + d * da * (255 - sa) / 255;
        out[channel] = (value / out_a).min(255) as u8;
    }
    out[3] = out_a.min(255) as u8; out
}

pub fn composite_pixel(layers: impl IntoIterator<Item = (Layer, [u8; 4])>) -> [u8; 4] {
    layers.into_iter().filter(|(layer, _)| layer.visible).fold([0; 4], |dst, (layer, pixel)| {
        let mask = layer.mask.as_ref().and_then(|m| m.first().copied()).unwrap_or(255);
        over(dst, pixel, layer.opacity, mask)
    })
}

#[cfg(test)]
mod tests {
    use super::*; use crate::Document;
    #[test] fn normal_over_respects_opacity_and_mask() { assert_eq!(over([0,0,255,255],[255,0,0,255],0,255),[0,0,255,255]); assert_eq!(over([0,0,255,255],[255,0,0,255],255,0),[0,0,255,255]); }
    #[test] fn blend_modes_are_deterministic() { assert_eq!(over_mode([100,100,100,255],[200,200,200,255],255,255,BlendMode::Multiply),[78,78,78,255]); assert_eq!(over_mode([100,100,100,255],[200,200,200,255],255,255,BlendMode::Screen),[222,222,222,255]); }
    #[test] fn hidden_layers_do_not_contribute() { let mut doc=Document::new(2,2).unwrap(); let id=doc.add_layer("Ink"); doc.layers[1].visible=false; assert_eq!(composite_pixel([(doc.layers[1].clone(),[255,0,0,255]),(doc.layers[0].clone(),[0,0,255,255])]),[0,0,255,255]); assert_eq!(id,doc.selected_layer); }
}
