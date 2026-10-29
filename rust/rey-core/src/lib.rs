use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use thiserror::Error;

pub const MAX_DOCUMENT_PIXELS: u64 = 144_000_000;
pub const TILE_SIZE: u32 = 256;

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct TileStore {
    width: u32,
    height: u32,
    tiles: BTreeMap<(u32, u32), Vec<u8>>,
}

impl TileStore {
    pub fn new(width: u32, height: u32) -> Result<Self, DocumentError> {
        if width == 0 || height == 0 { return Err(DocumentError::InvalidDimensions); }
        if u64::from(width) * u64::from(height) > MAX_DOCUMENT_PIXELS { return Err(DocumentError::TooLarge); }
        Ok(Self { width, height, tiles: BTreeMap::new() })
    }

    pub fn tile_count(&self) -> usize { self.tiles.len() }
    pub fn dimensions(&self) -> (u32, u32) { (self.width, self.height) }

    pub fn ensure_tile(&mut self, tile_x: u32, tile_y: u32) -> Result<&mut [u8], DocumentError> {
        let max_x = self.width.div_ceil(TILE_SIZE);
        let max_y = self.height.div_ceil(TILE_SIZE);
        if tile_x >= max_x || tile_y >= max_y { return Err(DocumentError::MissingTile); }
        let w = (self.width - tile_x * TILE_SIZE).min(TILE_SIZE);
        let h = (self.height - tile_y * TILE_SIZE).min(TILE_SIZE);
        Ok(self.tiles.entry((tile_x, tile_y)).or_insert_with(|| vec![0; (w * h * 4) as usize]).as_mut_slice())
    }
}


#[derive(Debug, Error, PartialEq)]
pub enum DocumentError {
    #[error("document dimensions must be positive")]
    InvalidDimensions,
    #[error("document is too large")]
    TooLarge,
    #[error("layer does not exist")]
    MissingLayer,
    #[error("tile is outside document bounds")]
    MissingTile,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct Layer {
    pub id: u64,
    pub name: String,
    pub visible: bool,
    pub opacity: u8,
    pub mask: Option<Vec<u8>>,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub enum Command {
    AddLayer { name: String },
    RenameLayer { id: u64, name: String },
    SetOpacity { id: u64, opacity: u8 },
    MoveLayer { id: u64, offset: isize },
    SetVisibility { id: u64, visible: bool },
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct Document {
    pub width: u32,
    pub height: u32,
    pub layers: Vec<Layer>,
    pub selected_layer: u64,
    next_id: u64,
}

impl Document {
    pub fn new(width: u32, height: u32) -> Result<Self, DocumentError> {
        if width == 0 || height == 0 { return Err(DocumentError::InvalidDimensions); }
        if u64::from(width) * u64::from(height) > MAX_DOCUMENT_PIXELS { return Err(DocumentError::TooLarge); }
        Ok(Self { width, height, layers: vec![Layer { id: 1, name: "Background".into(), visible: true, opacity: 255, mask: None }], selected_layer: 1, next_id: 2 })
    }

    pub fn apply(&mut self, command: Command) -> Result<(), DocumentError> {
        match command {
            Command::AddLayer { name } => { self.add_layer(name); Ok(()) }
            Command::RenameLayer { id, name } => self.rename_layer(id, name),
            Command::SetOpacity { id, opacity } => self.set_opacity(id, opacity),
            Command::MoveLayer { id, offset } => self.move_layer(id, offset),
            Command::SetVisibility { id, visible } => { self.layer_mut(id)?.visible = visible; Ok(()) }
        }
    }

    pub fn add_layer(&mut self, name: impl Into<String>) -> u64 {
        let id = self.next_id; self.next_id += 1;
        self.layers.push(Layer { id, name: name.into(), visible: true, opacity: 255, mask: None }); self.selected_layer = id; id
    }

    pub fn rename_layer(&mut self, id: u64, name: impl Into<String>) -> Result<(), DocumentError> {
        self.layer_mut(id)?.name = name.into(); Ok(())
    }

    pub fn set_opacity(&mut self, id: u64, opacity: u8) -> Result<(), DocumentError> {
        self.layer_mut(id)?.opacity = opacity; Ok(())
    }

    pub fn move_layer(&mut self, id: u64, offset: isize) -> Result<(), DocumentError> {
        let from = self.layers.iter().position(|l| l.id == id).ok_or(DocumentError::MissingLayer)?;
        let to = (from as isize + offset).clamp(0, self.layers.len() as isize - 1) as usize;
        let layer = self.layers.remove(from); self.layers.insert(to, layer); Ok(())
    }

    fn layer_mut(&mut self, id: u64) -> Result<&mut Layer, DocumentError> { self.layers.iter_mut().find(|l| l.id == id).ok_or(DocumentError::MissingLayer) }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn validates_dimensions() { assert_eq!(Document::new(0, 1), Err(DocumentError::InvalidDimensions)); assert_eq!(Document::new(12_001, 12_001), Err(DocumentError::TooLarge)); }
    #[test] fn layer_commands_preserve_stable_ids() { let mut d=Document::new(10,10).unwrap(); let id=d.add_layer("Paint"); d.rename_layer(id,"Ink").unwrap(); d.set_opacity(id,128).unwrap(); d.move_layer(id,-1).unwrap(); assert_eq!(d.layers.iter().find(|l|l.id==id).unwrap().name,"Ink"); assert_eq!(d.layers.iter().find(|l|l.id==id).unwrap().opacity,128); }
    #[test] fn project_serializes() { let d=Document::new(10,10).unwrap(); let json=serde_json::to_string(&d).unwrap(); let copy:Document=serde_json::from_str(&json).unwrap(); assert_eq!(d,copy); }
    #[test] fn commands_validate_at_one_boundary() { let mut d=Document::new(10,10).unwrap(); d.apply(Command::AddLayer { name: "Ink".into() }).unwrap(); let id=d.selected_layer; d.apply(Command::SetOpacity { id, opacity: 100 }).unwrap(); d.apply(Command::SetVisibility { id, visible: false }).unwrap(); assert_eq!(d.layers[1].opacity,100); assert!(!d.layers[1].visible); assert!(d.apply(Command::RenameLayer { id:999, name:"bad".into() }).is_err()); }
    #[test] fn sparse_tiles_allocate_only_when_touched() { let mut tiles=TileStore::new(1000,1000).unwrap(); assert_eq!(tiles.tile_count(),0); tiles.ensure_tile(2,3).unwrap(); assert_eq!(tiles.tile_count(),1); assert_eq!(tiles.ensure_tile(99,0),Err(DocumentError::MissingTile)); }
}
