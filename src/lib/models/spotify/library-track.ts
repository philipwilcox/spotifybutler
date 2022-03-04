import {Track} from "./track";
import {deserialize} from "cerialize";

export class LibraryTrack {
    @deserialize added_at: Date;
    @deserialize track: Track;
}
