import {Track} from "./track.js";
import {deserialize} from "cerialize";

export class LibraryTrack {
    // TODO: this is actually being parsed as a string still... cerialize isn't doing any type conversion...
    @deserialize added_at: Date;
    @deserialize track: Track;
}
